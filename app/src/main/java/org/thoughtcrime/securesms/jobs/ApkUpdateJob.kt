package org.thoughtcrime.securesms.jobs

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.Hex
import org.signal.core.util.JsonUtils
import org.signal.core.util.forEach
import org.signal.core.util.getDownloadManager
import org.signal.core.util.logging.Log
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireString
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.apkupdate.ApkUpdateDownloadManagerReceiver
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.Environment
import org.thoughtcrime.securesms.util.FileUtils
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Designed to be a periodic job that checks for new app updates when the user is running a build that
 * is distributed outside of the play store (like our website build).
 *
 * It uses the DownloadManager to actually download the APK for some easy reliability, considering the
 * file it's downloading it rather large (70+ MB).
 */
class ApkUpdateJob private constructor(
  parameters: Parameters,
  /**
   * Tellomi（tellomi/tellomi#1138）：「必须更新」阻断页发起的检查允许用移动数据下载（需求 3.4「移动网络」），
   * 而且会把一条还在等 Wi-Fi 的旧下载改排成可用流量的。后台定期检查仍只走 Wi-Fi（#1038）。
   */
  val allowMeteredNetwork: Boolean
) : BaseJob(parameters) {

  companion object {
    const val KEY = "UpdateApkJob"
    private val TAG = Log.tag(ApkUpdateJob::class.java)

    private const val KEY_ALLOW_METERED_NETWORK = "allow_metered_network"
  }

  @JvmOverloads
  constructor(allowMeteredNetwork: Boolean = false) : this(
    Parameters.Builder()
      .setQueue(KEY)
      .setMaxInstancesForFactory(2)
      .addConstraint(NetworkConstraint.KEY)
      .setMaxAttempts(2)
      .build(),
    allowMeteredNetwork
  )

  override fun serialize(): ByteArray? {
    // 上游这里是 null；不带流量标记的仍写 null，和已经排在队列里的旧任务同一种形状。
    return if (allowMeteredNetwork) JsonJobData.Builder().putBoolean(KEY_ALLOW_METERED_NETWORK, true).serialize() else null
  }

  override fun getFactoryKey(): String = KEY

  @Throws(IOException::class)
  public override fun onRun() {
    if (!BuildConfig.MANAGES_APP_UPDATES) {
      Log.w(TAG, "Not an app-updating build! Exiting.")
      return
    }

    Log.d(TAG, "Checking for APK update at ${BuildConfig.APK_UPDATE_MANIFEST_URL}")

    val client = OkHttpClient()
    val request = Request.Builder().url(BuildConfig.APK_UPDATE_MANIFEST_URL).build()

    val rawUpdateDescriptor: String = client.newCall(request).execute().use { response ->
      if (!response.isSuccessful || response.body == null) {
        throw IOException("Failed to read update descriptor")
      }
      response.body!!.string()
    }

    val updateDescriptor: UpdateDescriptor = JsonUtils.fromJson(rawUpdateDescriptor, UpdateDescriptor::class.java)

    if (updateDescriptor.versionCode <= 0 || updateDescriptor.versionName == null || updateDescriptor.url == null || updateDescriptor.digest == null) {
      Log.w(TAG, "Invalid update descriptor! $updateDescriptor")
      return
    } else {
      Log.d(TAG, "Got descriptor: $updateDescriptor")
    }

    val newerVersionAvailable = shouldUpdate(getCurrentAppVersionCode(), updateDescriptor, SignalStore.apkUpdate.lastApkUploadTime, Environment.IS_WEBSITE)
    // Tellomi（#1138）：「关于」页与阻断页据此显示新版本号。
    SignalStore.apkUpdate.availableUpdateVersionName = if (newerVersionAvailable) updateDescriptor.versionName else null

    if (newerVersionAvailable) {
      Log.i(TAG, "Newer version code available. Current: (versionCode: ${getCurrentAppVersionCode()}, uploadTime: ${SignalStore.apkUpdate.lastApkUploadTime}), Update: (versionCode: ${updateDescriptor.versionCode}, uploadTime: ${updateDescriptor.uploadTimestamp})")
      val digest: ByteArray = Hex.fromStringCondensed(updateDescriptor.digest)
      val downloadStatus: DownloadStatus = getDownloadStatus(updateDescriptor.url, digest)

      Log.i(TAG, "Download status: ${downloadStatus.status}")

      if (downloadStatus.status == DownloadStatus.Status.COMPLETE) {
        Log.i(TAG, "Download status complete, notifying...")
        handleDownloadComplete(downloadStatus.downloadId)
      } else if (downloadStatus.status == DownloadStatus.Status.MISSING) {
        Log.i(TAG, "Download status missing, starting download...")
        handleDownloadStart(updateDescriptor.url, updateDescriptor.versionName, digest, updateDescriptor.uploadTimestamp ?: 0)
      } else if (allowMeteredNetwork && !downloadStatus.isRunning) {
        // Tellomi（#1138）：后台排的下载只许走 Wi-Fi，没有 Wi-Fi 时会一直停在排队状态。必须更新时改排成可用流量的。
        // 还没开始下（或暂停中）才这样做，正在下的不打断。
        Log.i(TAG, "Required update: re-enqueuing a download that is not running so it may use metered networks.")
        context.getDownloadManager().remove(downloadStatus.downloadId)
        SignalStore.apkUpdate.clearDownloadAttributes()
        handleDownloadStart(updateDescriptor.url, updateDescriptor.versionName, digest, updateDescriptor.uploadTimestamp ?: 0)
      }
    } else {
      Log.d(TAG, "Version code is the same or older than our own. Current: (versionCode: ${getCurrentAppVersionCode()}, uploadTime: ${SignalStore.apkUpdate.lastApkUploadTime}), Update: (versionCode: ${updateDescriptor.versionCode}, uploadTime: ${updateDescriptor.uploadTimestamp})")
    }

    SignalStore.apkUpdate.lastSuccessfulCheck = System.currentTimeMillis()
  }

  public override fun onShouldRetry(e: Exception): Boolean {
    return e is IOException
  }

  override fun onFailure() {
    Log.w(TAG, "Update check failed")
  }

  @Throws(PackageManager.NameNotFoundException::class)
  private fun getCurrentAppVersionCode(): Int {
    val packageManager = context.packageManager
    val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
    return packageInfo.versionCode
  }

  private fun getDownloadStatus(uri: String, remoteDigest: ByteArray): DownloadStatus {
    val pendingDownloadId: Long = SignalStore.apkUpdate.downloadId
    val pendingDigest: ByteArray? = SignalStore.apkUpdate.digest

    if (pendingDownloadId == -1L || pendingDigest == null || !MessageDigest.isEqual(pendingDigest, remoteDigest)) {
      SignalStore.apkUpdate.clearDownloadAttributes()
      return DownloadStatus(DownloadStatus.Status.MISSING, -1)
    }

    val query = DownloadManager.Query().apply {
      setFilterByStatus(DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_SUCCESSFUL)
      setFilterById(pendingDownloadId)
    }

    context.getDownloadManager().query(query).forEach { cursor ->
      val jobStatus = cursor.requireInt(DownloadManager.COLUMN_STATUS)
      val jobRemoteUri = cursor.requireString(DownloadManager.COLUMN_URI)
      val downloadId = cursor.requireLong(DownloadManager.COLUMN_ID)

      if (jobRemoteUri == uri && downloadId == pendingDownloadId) {
        return if (jobStatus == DownloadManager.STATUS_SUCCESSFUL) {
          val digest = getDigestForDownloadId(downloadId)
          if (digest != null && MessageDigest.isEqual(digest, remoteDigest)) {
            DownloadStatus(DownloadStatus.Status.COMPLETE, downloadId)
          } else {
            Log.w(TAG, "Found downloadId $downloadId, but the digest doesn't match! Considering it missing.")
            SignalStore.apkUpdate.clearDownloadAttributes()
            DownloadStatus(DownloadStatus.Status.MISSING, downloadId)
          }
        } else {
          DownloadStatus(DownloadStatus.Status.PENDING, downloadId, isRunning = jobStatus == DownloadManager.STATUS_RUNNING)
        }
      }
    }

    return DownloadStatus(DownloadStatus.Status.MISSING, -1)
  }

  private fun handleDownloadStart(uri: String?, versionName: String?, digest: ByteArray, uploadTimestamp: Long) {
    deleteExistingDownloadedApks(context)

    val downloadRequest = DownloadManager.Request(Uri.parse(uri)).apply {
      if (allowMeteredNetwork) {
        // Tellomi（#1138）：必须更新允许用流量。Request 的默认值就是任何网络、计费网络也可以，这里写明。
        setAllowedOverMetered(true)
      } else {
        setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
      }
      // Tellomi：这三行是系统下载通知里用户能看到的字，上游写死了 Signal。
      // 文件名与下面 deleteExistingDownloadedApks 的前缀必须一起改，否则旧包清不掉、越攒越多。
      setTitle("正在下载 Tellomi 更新")
      setDescription("Tellomi $versionName")
      setDestinationInExternalFilesDir(context, null, "tellomi-update.apk")
      setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
    }

    val downloadId = context.getDownloadManager().enqueue(downloadRequest)
    // DownloadManager will trigger [UpdateApkReadyListener] when finished via a broadcast

    SignalStore.apkUpdate.setDownloadAttributes(downloadId, digest, uploadTimestamp)
  }

  private fun handleDownloadComplete(downloadId: Long) {
    val intent = Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
    intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId)
    ApkUpdateDownloadManagerReceiver().onReceive(context, intent)
  }

  private fun getDigestForDownloadId(downloadId: Long): ByteArray? {
    return try {
      FileInputStream(context.getDownloadManager().openDownloadedFile(downloadId).fileDescriptor).use { stream ->
        FileUtils.getFileDigest(stream)
      }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to get digest for downloadId! $downloadId", e)
      null
    }
  }

  private fun deleteExistingDownloadedApks(context: Context) {
    val directory = context.getExternalFilesDir(null)
    if (directory == null) {
      Log.w(TAG, "Failed to read external files directory.")
      return
    }

    for (file in directory.listFiles() ?: emptyArray()) {
      if (file.name.startsWith("tellomi-update")) {
        if (file.delete()) {
          Log.d(TAG, "Deleted " + file.name)
        }
      }
    }
  }

  private fun shouldUpdate(currentVersionCode: Int, updateDescriptor: UpdateDescriptor, lastApkUploadTime: Long, isWebsite: Boolean): Boolean {
    return if (isWebsite) {
      return updateDescriptor.versionCode > currentVersionCode
    } else {
      return updateDescriptor.versionCode > currentVersionCode || (updateDescriptor.versionCode == currentVersionCode && (updateDescriptor.uploadTimestamp ?: 0) > lastApkUploadTime)
    }
  }

  private data class UpdateDescriptor(
    @JsonProperty
    val versionCode: Int = 0,

    @JsonProperty
    val versionName: String? = null,

    @JsonProperty
    val url: String? = null,

    @JsonProperty("sha256sum")
    val digest: String? = null,

    @JsonProperty
    val uploadTimestamp: Long? = null
  )

  private class DownloadStatus(val status: Status, val downloadId: Long, val isRunning: Boolean = false) {
    enum class Status {
      PENDING,
      COMPLETE,
      MISSING
    }
  }

  class Factory : Job.Factory<ApkUpdateJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): ApkUpdateJob {
      val data = JsonJobData.deserialize(serializedData)
      return ApkUpdateJob(parameters, data.getBooleanOrDefault(KEY_ALLOW_METERED_NETWORK, false))
    }
  }
}
