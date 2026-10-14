package org.thoughtcrime.securesms.conversation

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.button.MaterialButton
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.FixedRoundedCornerBottomSheetDialogFragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.fragments.findListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bottom sheet dialog that allows selecting a timestamp after the current time for
 * scheduling a message send.
 *
 * Will call [ScheduleCallback.onScheduleSend] with the selected time, if called with [showSchedule]
 * Will call [RescheduleCallback.onReschedule] with the selected time, if called with [showReschedule]
 *
 * Tellomi（tellomi/tellomi#1601，M-4，O8 照 Telegram 两端）：上游是「日期」「时间」两行、各自弹系统的日期 / 时间选择器，
 * 加时区说明和「选了过去的时间」确认；换成 Telegram 安卓 `AlertsCreator.createScheduleDatePickerDialog` 的样子
 * （只读，代码是自己写的）：标题（收藏里「设置提醒」）、日 / 时 / 分三个滚轮、整宽按钮「今天 HH:mm 发送 / 某天 HH:mm 发送」；
 * 早于最早时间的选不到，点按钮时再推一次、不提示。规则见 [TellomiScheduleWheels]。
 */
class ScheduleMessageTimePickerBottomSheet : FixedRoundedCornerBottomSheetDialogFragment() {
  override val peekHeightPercentage: Float = 0.66f
  override val themeResId: Int = R.style.Widget_Signal_FixedRoundedCorners_Messages

  private lateinit var dayPicker: NumberPicker
  private lateinit var hourPicker: NumberPicker
  private lateinit var minutePicker: NumberPicker
  private lateinit var confirmButton: MaterialButton

  private val isReminder: Boolean
    get() = arguments?.getBoolean(KEY_REMINDER) == true

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return inflater.inflate(R.layout.tellomi_schedule_time_wheels_bottom_sheet, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val now = System.currentTimeMillis()
    view.findViewById<TextView>(R.id.tellomi_schedule_title).setText(
      if (isReminder) R.string.TellomiScheduleTimePicker__set_reminder else R.string.ScheduleMessageTimePickerBottomSheet__dialog_title
    )
    dayPicker = view.findViewById(R.id.tellomi_schedule_day)
    hourPicker = view.findViewById(R.id.tellomi_schedule_hour)
    minutePicker = view.findViewById(R.id.tellomi_schedule_minute)
    confirmButton = view.findViewById(R.id.tellomi_schedule_confirm)

    dayPicker.minValue = 0
    dayPicker.maxValue = TellomiScheduleWheels.MAX_DAYS
    dayPicker.wrapSelectorWheel = false
    dayPicker.displayedValues = Array(TellomiScheduleWheels.MAX_DAYS + 1) { dayLabel(it, now) }
    hourPicker.minValue = 0
    hourPicker.maxValue = 23
    hourPicker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
    minutePicker.minValue = 0
    minutePicker.maxValue = 59
    minutePicker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }

    val initialTime = arguments?.takeIf { it.containsKey(KEY_INITIAL_TIME) }?.getLong(KEY_INITIAL_TIME)
    show(TellomiScheduleWheels.selection(TellomiScheduleWheels.clamp(initialTime ?: TellomiScheduleWheels.defaultTime(now), now), now))

    val onChange = NumberPicker.OnValueChangeListener { _, _, _ ->
      show(TellomiScheduleWheels.settle(currentSelection(), System.currentTimeMillis()))
    }
    dayPicker.setOnValueChangedListener(onChange)
    hourPicker.setOnValueChangedListener(onChange)
    minutePicker.setOnValueChangedListener(onChange)

    confirmButton.setOnClickListener { scheduleMessageSend() }
  }

  private fun currentSelection(): TellomiScheduleWheels.Selection {
    return TellomiScheduleWheels.Selection(dayOffset = dayPicker.value, hour = hourPicker.value, minute = minutePicker.value)
  }

  private fun show(selection: TellomiScheduleWheels.Selection) {
    dayPicker.value = selection.dayOffset
    hourPicker.value = selection.hour
    minutePicker.value = selection.minute
    confirmButton.text = confirmText(TellomiScheduleWheels.time(selection, System.currentTimeMillis()), System.currentTimeMillis())
  }

  private fun dayLabel(dayOffset: Int, now: Long): String {
    if (dayOffset == 0) {
      return getString(R.string.DateUtils_today)
    }
    val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(dayOffset.toLong())
    val sameYear = day.year == Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).year
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (sameYear) "EEEMMMd" else "MMMdyyyy")
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(day)
  }

  private fun confirmText(timeMs: Long, now: Long): String {
    val time = DateFormat.getTimeFormat(requireContext()).format(timeMs)
    val dateTime = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
    return when (TellomiScheduleWheels.day(timeMs, now)) {
      TellomiScheduleWheels.Day.TODAY -> getString(if (isReminder) R.string.TellomiScheduleTimePicker__remind_today_at else R.string.TellomiScheduleTimePicker__send_today_at, time)
      TellomiScheduleWheels.Day.THIS_YEAR, TellomiScheduleWheels.Day.OTHER_YEAR -> {
        val skeleton = if (TellomiScheduleWheels.day(timeMs, now) == TellomiScheduleWheels.Day.THIS_YEAR) "MMMd" else "MMMdyyyy"
        val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), Locale.getDefault()).format(dateTime)
        getString(if (isReminder) R.string.TellomiScheduleTimePicker__remind_on_at else R.string.TellomiScheduleTimePicker__send_on_at, date, time)
      }
    }
  }

  private fun getSelectedTimestamp(): Long {
    val now = System.currentTimeMillis()
    return TellomiScheduleWheels.clamp(TellomiScheduleWheels.time(currentSelection(), now), now)
  }

  private fun scheduleMessageSend() {
    dismissAllowingStateLoss()
    val messageId = arguments?.takeIf { it.containsKey(KEY_MESSAGE_ID) }?.getLong(KEY_MESSAGE_ID)
    if (messageId == null) {
      findListener<ScheduleCallback>()?.onScheduleSend(getSelectedTimestamp())
    } else {
      val selectedTime = getSelectedTimestamp()
      if (selectedTime != arguments?.getLong(KEY_INITIAL_TIME)) {
        findListener<RescheduleCallback>()?.onReschedule(selectedTime, messageId)
      }
    }
  }

  interface ScheduleCallback {
    fun onScheduleSend(scheduledTime: Long)
  }

  interface RescheduleCallback {
    fun onReschedule(scheduledTime: Long, messageId: Long)
  }

  companion object {

    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_INITIAL_TIME = "initial_time"
    private const val KEY_REMINDER = "tellomi_reminder"

    /** @param isReminder 收藏（给自己）里：标题「设置提醒」、按钮「今天 HH:mm 提醒」（同 Telegram 两端）。 */
    @JvmStatic
    @JvmOverloads
    fun showSchedule(fragmentManager: FragmentManager, isReminder: Boolean = false) {
      val fragment = ScheduleMessageTimePickerBottomSheet().apply {
        arguments = Bundle().apply { putBoolean(KEY_REMINDER, isReminder) }
      }

      fragment.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }

    @JvmStatic
    @JvmOverloads
    fun showReschedule(fragmentManager: FragmentManager, messageId: Long, initialTime: Long, isReminder: Boolean = false) {
      val args = Bundle().apply {
        putLong(KEY_MESSAGE_ID, messageId)
        putLong(KEY_INITIAL_TIME, initialTime)
        putBoolean(KEY_REMINDER, isReminder)
      }

      val fragment = ScheduleMessageTimePickerBottomSheet().apply {
        arguments = args
      }

      fragment.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }
  }
}
