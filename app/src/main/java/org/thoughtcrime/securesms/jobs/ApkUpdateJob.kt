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
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.apkupdate.ApkUpdateDownloadManagerReceiver
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.region.TellomiRegions
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
  val allowMeteredNetwork: Boolean,
  /**
   * Tellomi（taishi 审查 b14 包 7 不阻塞 2）：用户在阻断页的「重试」上明确点了重来。已经允许流量、却卡着不动（没在下）的那条下载
   * 也删掉重排；否则重试后页面还跟着同一条卡住的下载，90 秒后又转失败。自动的检查仍不动暂停中的流量下载（要改 3）。
   */
  val restartStuckDownload: Boolean = false
) : BaseJob(parameters) {

  companion object {
    const val KEY = "UpdateApkJob"
    private val TAG = Log.tag(ApkUpdateJob::class.java)

    private const val KEY_ALLOW_METERED_NETWORK = "allow_metered_network"
    private const val KEY_RESTART_STUCK_DOWNLOAD = "restart_stuck_download"

    private val TRUSTED_VERSION_NAME = Regex("^\\d+(\\.\\d+){1,3}$")

    /**
     * Tellomi（tellomi/tellomi#1138，taishi 审查 b14 要改 3）：阻断页发起的检查遇到一条已存在、还没在下的下载时，
     * 要不要删掉它、改排成可用流量的。只改排只许 Wi-Fi 的那种；已经允许流量的（比如信号差暂停着）不动，
     * 否则网络越差越从 0 重下、越费流量。
     */
    @JvmStatic
    @JvmOverloads
    fun shouldReenqueueForMeteredNetwork(allowMeteredNetwork: Boolean, existingIsRunning: Boolean, existingAllowsMetered: Boolean, restartStuckDownload: Boolean = false): Boolean {
      return allowMeteredNetwork && !existingIsRunning && (!existingAllowsMetered || restartStuckDownload)
    }

    /**
     * Tellomi（tellomi/tellomi#1138，taishi 审查 b14 不阻塞 3）：清单还没签名（#1136），versionName 会原样显示在阻断页和「关于」页，
     * url 会被拿去下载。存之前先卡一下形状：版本号只许「数字.数字」，下载地址只许和清单同一个 https 源。
     */
    @JvmStatic
    fun isTrustedDescriptor(versionName: String?, url: String?, manifestUrl: String): Boolean {
      if (versionName == null || versionName.length > 20 || !TRUSTED_VERSION_NAME.matches(versionName)) {
        return false
      }
      val manifest = runCatching { java.net.URI(manifestUrl) }.getOrNull() ?: return false
      if (manifest.scheme != "https" || manifest.host.isNullOrEmpty()) {
        return false
      }
      return url != null && url.startsWith("https://${manifest.host}/")
    }
  }

  @JvmOverloads
  constructor(allowMeteredNetwork: Boolean = false, restartStuckDownload: Boolean = false) : this(
    Parameters.Builder()
      .setQueue(KEY)
      .setMaxInstancesForFactory(2)
      .addConstraint(NetworkConstraint.KEY)
      .setMaxAttempts(2)
      .build(),
    allowMeteredNetwork,
    restartStuckDownload
  )

  override fun serialize(): ByteArray? {
    // 上游这里是 null；不带流量标记的仍写 null，和已经排在队列里的旧任务同一种形状。
    if (!allowMeteredNetwork) {
      return null
    }
    return JsonJobData.Builder()
      .putBoolean(KEY_ALLOW_METERED_NETWORK, true)
      .putBoolean(KEY_RESTART_STUCK_DOWNLOAD, restartStuckDownload)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  @Throws(IOException::class)
  public override fun onRun() {
    if (!BuildConfig.MANAGES_APP_UPDATES) {
      Log.w(TAG, "Not an app-updating build! Exiting.")
      return
    }

    // Tellomi（#1055）：清单地址从当前区取（只有 website 档有）
    val manifestUrl = TellomiRegions.current().apkUpdateManifestUrl
    Log.d(TAG, "Checking for APK update at $manifestUrl")

    val client = OkHttpClient()
    val request = Request.Builder().url(manifestUrl!!).build()

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
    } else if (!isTrustedDescriptor(updateDescriptor.versionName, updateDescriptor.url, manifestUrl)) {
      Log.w(TAG, "Update descriptor has an unexpected version name or download URL! $updateDescriptor")
      return
    } else {
      Log.d(TAG, "Got descriptor: $updateDescriptor")
    }

    val newerVersionAvailable = shouldUpdate(getCurrentAppVersionCode(), updateDescriptor, SignalStore.apkUpdate.lastApkUploadTime, Environment.IS_WEBSITE)
    // Tellomi（#1138）：「关于」页与阻断页据此显示新版本号。
    if (newerVersionAvailable) {
      SignalStore.apkUpdate.setAvailableUpdate(updateDescriptor.versionCode, updateDescriptor.versionName)
    } else {
      SignalStore.apkUpdate.clearAvailableUpdate()
    }

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
      } else if (shouldReenqueueForMeteredNetwork(allowMeteredNetwork, downloadStatus.isRunning, SignalStore.apkUpdate.downloadAllowsMetered, restartStuckDownload)) {
        // Tellomi（#1138）：后台排的下载只许走 Wi-Fi，没有 Wi-Fi 时会一直停在排队状态。必须更新时改排成可用流量的。
        // 只动只许 Wi-Fi、又不在下的那条；已经允许流量的（暂停中也算）不动（taishi 审查 b14 要改 3）。
        Log.i(TAG, "Required update: re-enqueuing a Wi-Fi-only download that is not running so it may use metered networks.")
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
        // 默认允许漫游；上游只许 Wi-Fi，等于不漫游。国际漫游时别下这约 120 MB（taishi 审查 b14 不阻塞 1）。
        setAllowedOverRoaming(false)
      } else {
        setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
      }
      // Tellomi：这三行是系统下载通知里用户能看到的字，上游写死了 Signal。
      // 文件名与下面 deleteExistingDownloadedApks 的前缀必须一起改，否则旧包清不掉、越攒越多。
      // Tellomi（#1138）：流量下载的通知现在看得见了（taishi 审查 b14 不阻塞 2），标题跟着 App 语言，不再写死简体中文。
      setTitle(context.getString(R.string.TellomiUpdateRequired__download_notification_title))
      setDescription("Tellomi $versionName")
      setDestinationInExternalFilesDir(context, null, "tellomi-update.apk")
      // 上游为 Wi-Fi 后台下载设了 HIDDEN。用户点出来的流量下载要在通知栏看得见进度（taishi 审查 b14 不阻塞 2；
      // 能不能在通知里取消看各家 ROM，不作承诺，taishi 审查包 7 不阻塞 5）。
      setNotificationVisibility(if (allowMeteredNetwork) DownloadManager.Request.VISIBILITY_VISIBLE else DownloadManager.Request.VISIBILITY_HIDDEN)
    }

    val downloadId = context.getDownloadManager().enqueue(downloadRequest)
    // DownloadManager will trigger [UpdateApkReadyListener] when finished via a broadcast

    SignalStore.apkUpdate.setDownloadAttributes(downloadId, digest, uploadTimestamp, allowsMetered = allowMeteredNetwork)
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
      return ApkUpdateJob(parameters, data.getBooleanOrDefault(KEY_ALLOW_METERED_NETWORK, false), data.getBooleanOrDefault(KEY_RESTART_STUCK_DOWNLOAD, false))
    }
  }
}
