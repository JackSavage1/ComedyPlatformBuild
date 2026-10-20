package com.comedyplatform.mictracker.core.rules

import com.comedyplatform.mictracker.core.model.Mic
import java.util.Locale

/**
 * How a mic's raw fields turn into display text (spec rules 1, 2, 10, 19)
 * and how mics are sorted within a day.
 */
object MicRules {

    /** Rule 1: `display_time` if present, else `start_time` (else ""). */
    fun timeText(displayTime: String?, startTime: String?): String =
        TextRules.trimmedOrNull(displayTime) ?: TextRules.trimmedOrNull(startTime) ?: ""

    /** Rule 2: the cost text, or "Free/TBD" when there isn't one. */
    fun costText(cost: String?): String = TextRules.trimmedOrNull(cost) ?: "Free/TBD"

    /** Rule 10: "5 min sets" or "Set length: N/A". */
    fun setLengthText(minutes: Int?): String =
        if (minutes != null && minutes > 0) "$minutes min sets" else "Set length: N/A"

    /**
     * Minutes after midnight for a stored start time, used for sorting.
     *
     * Accepts the stored 24-hour "HH:MM" (also "H:MM" and "HH:MM:SS") and —
     * because scrapers sometimes store it — "7:00 PM", "7pm", "7:30pm".
     * Returns null for anything else so malformed times sort last.
     */
    fun minutesSinceMidnight(raw: String?): Int? {
        var text = TextRules.trimmedOrNull(raw)?.lowercase() ?: return null

        var meridiem: String? = null
        if (text.endsWith("am") || text.endsWith("pm")) {
            meridiem = text.takeLast(2)
            text = text.dropLast(2).trim()
        }

        val parts = text.split(":")
        if (parts.size !in 1..3) return null
        if (parts.any { part -> part.isEmpty() || !part.all { it in '0'..'9' } }) return null

        val hour = parts[0].toIntOrNull() ?: return null
        var minute = 0
        if (parts.size >= 2) {
            if (parts[1].length != 2) return null
            minute = parts[1].toIntOrNull() ?: return null
        } else if (meridiem == null) {
            return null // a bare "19" isn't a valid stored time
        }
        if (parts.size == 3) {
            if (parts[2].length != 2) return null
            val seconds = parts[2].toIntOrNull() ?: return null
            if (seconds >= 60) return null
        }
        if (minute >= 60) return null

        if (meridiem != null) {
            if (hour !in 1..12) return null
            val hour24 = (hour % 12) + if (meridiem == "pm") 12 else 0
            return hour24 * 60 + minute
        }
        if (hour >= 24) return null
        return hour * 60 + minute
    }

    /**
     * Spec 3.5: sort by start time parsed to minutes; unparseable times last;
     * ties by name (case-insensitive), then id so the order never jumps.
     */
    val byStartTime: Comparator<Mic> = Comparator { a, b ->
        val left = minutesSinceMidnight(a.startTime)
        val right = minutesSinceMidnight(b.startTime)
        when {
            left != null && right != null && left != right -> left.compareTo(right)
            left != null && right == null -> -1
            left == null && right != null -> 1
            else -> {
                val byName = a.displayName.lowercase().compareTo(b.displayName.lowercase())
                if (byName != 0) byName else a.id.compareTo(b.id)
            }
        }
    }

    fun sortedByStartTime(mics: List<Mic>): List<Mic> = mics.sortedWith(byStartTime)

    /** Mon → Sun, then by start time inside each day. Unknown days go last. */
    val byDayThenTime: Comparator<Mic> = Comparator { a, b ->
        val byDay = Weekdays.sortIndex(a.dayOfWeek).compareTo(Weekdays.sortIndex(b.dayOfWeek))
        if (byDay != 0) byDay else byStartTime.compare(a, b)
    }

    // ---------------------------------------------------------------------
    // Rule 19: converting a picked time to stored + display text.
    // ---------------------------------------------------------------------

    /** The stored, zero-padded 24-hour start time: (19, 0) → "19:00". */
    fun startTimeString(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour, minute)

    /** The friendly display time ("h:mm a" in English): (19, 0) → "7:00 PM", (0, 5) → "12:05 AM". */
    fun displayTimeString(hour: Int, minute: Int): String {
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val suffix = if (hour < 12) "AM" else "PM"
        return String.format(Locale.US, "%d:%02d %s", hour12, minute, suffix)
    }

    /** Splits a stored start time back into (hour, minute) for a time picker, or null. */
    fun hourAndMinute(startTime: String?): Pair<Int, Int>? {
        val total = minutesSinceMidnight(startTime) ?: return null
        return Pair(total / 60, total % 60)
    }
}
