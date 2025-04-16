package com.example.IoTPurifierBLEControlApp

import android.app.TimePickerDialog
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScheduleFragment : Fragment() {

    data class DaySchedule(
        val day: String,
        var startHour: Int? = null,
        var startMinute: Int? = null,
        var endHour: Int? = null,
        var endMinute: Int? = null,
        var isActive: Boolean = false
    ) {
        fun getStartTime(): String? =
            if (startHour != null && startMinute != null)
                String.format("%02d:%02d", startHour, startMinute)
            else null

        fun getDurationMinutes(): Int? {
            if (startHour == null || startMinute == null || endHour == null || endMinute == null) return null
            val start = startHour!! * 60 + startMinute!!
            val end = endHour!! * 60 + endMinute!!
            return end - start
        }
    }

    data class DayViews(val startBtn: Button, val endBtn: Button, val daySwitch: Switch)

    private val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val fullDays = mapOf(
        "Mon" to "Monday",
        "Tue" to "Tuesday",
        "Wed" to "Wednesday",
        "Thu" to "Thursday",
        "Fri" to "Friday",
        "Sat" to "Saturday",
        "Sun" to "Sunday"
    )
    private val daySchedules = mutableMapOf<String, DaySchedule>()

    private val scheduleViews = mutableMapOf<String, DayViews>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_schedule, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = view.findViewById<ConstraintLayout>(R.id.scheduleContainer)
        val saveButton = view.findViewById<Button>(R.id.saveScheduleButton)
        val clearBtn = view.findViewById<Button>(R.id.clearBtn)
        val syncBtn = view.findViewById<Button>(R.id.syncBtn)
        val sensorsBtn = view.findViewById<Button>(R.id.sensorsBtn)

        val viewIds = mutableListOf<Int>()

        for (day in days) {
            val itemView = layoutInflater.inflate(R.layout.day_fragment, container, false)
            itemView.id = View.generateViewId()
            container.addView(itemView)
            viewIds.add(itemView.id)

            val dayText = itemView.findViewById<TextView>(R.id.dayName)
            val startBtn = itemView.findViewById<Button>(R.id.startTimeButton)
            val endBtn = itemView.findViewById<Button>(R.id.endTimeButton)
            val switch = itemView.findViewById<Switch>(R.id.daySwitch)

            val schedule = DaySchedule(day)
            daySchedules[day] = schedule
            dayText.text = fullDays[day]

            scheduleViews[day] = DayViews(startBtn, endBtn, switch)

            startBtn.setOnClickListener {
                val cal = Calendar.getInstance()
                TimePickerDialog(requireContext(), { _, hour, minute ->
                    startBtn.text = String.format("%02d:%02d", hour, minute)
                    schedule.startHour = hour
                    schedule.startMinute = minute
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
            }

            endBtn.setOnClickListener {
                if (schedule.startHour == null) {
                    Toast.makeText(requireContext(), "Select start time first for $day", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                TimePickerDialog(requireContext(), { _, hour, minute ->
                    val isValid = hour > schedule.startHour!! || (hour == schedule.startHour && minute > schedule.startMinute!!)
                    if (!isValid) {
                        Toast.makeText(requireContext(), "End time must be after start time for $day", Toast.LENGTH_SHORT).show()
                        return@TimePickerDialog
                    }
                    endBtn.text = String.format("%02d:%02d", hour, minute)
                    schedule.endHour = hour
                    schedule.endMinute = minute
                }, schedule.startHour!!, schedule.startMinute!!, true).show()
            }

            switch.setOnCheckedChangeListener { _, isChecked ->
                schedule.isActive = isChecked
            }
        }

        val set = ConstraintSet()
        set.clone(container)

        for (i in viewIds.indices) {
            val currentId = viewIds[i]

            if (i == 0) {
                set.connect(currentId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            } else {
                val prevId = viewIds[i - 1]
                set.connect(currentId, ConstraintSet.TOP, prevId, ConstraintSet.BOTTOM, 0)
            }

            set.connect(currentId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            set.connect(currentId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        }

        set.applyTo(container)

        saveButton.setOnClickListener {
            sendSchedules()
        }
        clearBtn.setOnClickListener {
            clearSchedules()
            clearUi()
        }
        syncBtn.setOnClickListener {
            syncTime()
        }
        sensorsBtn.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
            requireActivity().findViewById<View>(R.id.main_content_group).visibility = View.VISIBLE
            requireActivity().findViewById<View>(R.id.schedule_fragment_container).visibility = View.GONE
        }
        val activity = activity as? MainActivity ?: return
        activity.sendData("SHOW")

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requireActivity().supportFragmentManager.popBackStack()
                requireActivity().findViewById<View>(R.id.main_content_group).visibility = View.VISIBLE
                requireActivity().findViewById<View>(R.id.schedule_fragment_container).visibility = View.GONE
            }
        })
    }

    fun updateScheduleFromEsp32(data: String) {
        // "Schedules:\nID:1|Mon|07:00|60|ON\nID:2|Tue|07:00|60|ON\nID:6|Sat|07:00|60|ON\nID:0|Sun|07:00|60|ON"
        val lines = data.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val scheduleLines = if (lines.firstOrNull()?.startsWith("Schedules:") == true) {
            lines.drop(1)
        } else {
            lines
        }

        requireActivity().runOnUiThread {
            for (line in scheduleLines) {
                val parts = line.split("|")
                if (parts.size != 5) continue
                val dayAbbrev = parts[1]
                val startTimeStr = parts[2]
                val durationMinutes = parts[3].toIntOrNull() ?: continue
                val switchState = parts[4]

                val timeParts = startTimeStr.split(":")
                if (timeParts.size != 2) continue
                val startHour = timeParts[0].toIntOrNull() ?: continue
                val startMinute = timeParts[1].toIntOrNull() ?: continue
                val totalMinutes = startHour * 60 + startMinute + durationMinutes
                val endHour = totalMinutes / 60
                val endMinute = totalMinutes % 60
                val endTimeStr = String.format("%02d:%02d", endHour, endMinute)

                scheduleViews[dayAbbrev]?.let { views ->
                    views.startBtn.text = startTimeStr
                    views.endBtn.text = endTimeStr
                    views.daySwitch.isChecked = switchState.equals("ON", ignoreCase = true)
                }
                daySchedules[dayAbbrev]?.apply {
                    this.startHour = startHour
                    this.startMinute = startMinute
                    this.endHour = endHour
                    this.endMinute = endMinute
                    this.isActive = switchState.equals("ON", ignoreCase = true)
                }
            }
        }
    }

    private fun sendSchedules() {
        val activity = activity as? MainActivity ?: return

        val validSchedules = daySchedules.values.filter {
            it.getStartTime() != null && it.getDurationMinutes() != null
        }

        if (validSchedules.isEmpty()) {
            Toast.makeText(requireContext(), "No valid schedules to send", Toast.LENGTH_SHORT).show()
            return
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var delay = 0L

        validSchedules.forEach { schedule ->
            val start = schedule.getStartTime()
            val duration = schedule.getDurationMinutes()
            val switchState = if (schedule.isActive) "ON" else "OFF"

            val message = "SETSCHEDULE|${schedule.day}|$start|$duration|$switchState"

            handler.postDelayed({
                activity.sendData(message)
            }, delay)

            delay += 200L
        }

        handler.postDelayed({
            if (isAdded) {
                Toast.makeText(requireContext(), "Schedule sent", Toast.LENGTH_SHORT).show()
            }
        }, delay)
    }


    private fun clearSchedules() {
        val activity = activity as? MainActivity ?: return
        activity.sendData("CLEAR")
        Toast.makeText(requireContext(), "Schedule cleared", Toast.LENGTH_SHORT).show()
        Log.d("Clear schedule func", "Schedule cleared")
    }

    private fun syncTime() {
        //TIMESET|2025-03-29|14:45:00"
        val activity = activity as? MainActivity ?: return
        val formatter = SimpleDateFormat("yyyy-MM-dd'|'HH:mm:ss", Locale.getDefault())
        val formattedDate = formatter.format(Date())
        val msg = "TIMESET|$formattedDate"
        activity.sendData(msg)
        Log.d("Sync time func", "Sent: ${msg}")
        Toast.makeText(requireContext(), "Time synced", Toast.LENGTH_SHORT).show()
    }
    private fun clearUi(){
        for (line in days){
            scheduleViews[line]?.let { views ->
                views.startBtn.text = getString(R.string.start_time)
                views.endBtn.text = getString(R.string.end_time)
                views.daySwitch.isChecked = false
            }
        }
    }
}
