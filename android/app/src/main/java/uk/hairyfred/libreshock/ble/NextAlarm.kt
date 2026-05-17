package uk.hairyfred.libreshock.ble

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Find the next enabled alarm to fire, given the current alarm list and the
 * current time. Returns the alarm and its next firing [LocalDateTime].
 *
 * One-shot alarms (weekdays == 0) fire today if their time hasn't passed, else
 * tomorrow. Repeating alarms fire on the next set weekday.
 */
fun nextEnabledAlarm(
    alarms: List<AlarmConfig>,
    now: LocalDateTime = LocalDateTime.now(),
): Pair<AlarmConfig, LocalDateTime>? {
    val active = alarms.filter { it.enabled }
    if (active.isEmpty()) return null
    val withFire = active.mapNotNull { a ->
        val fireAt = nextFireTime(a, now) ?: return@mapNotNull null
        a to fireAt
    }
    return withFire.minByOrNull { it.second }
}

private fun nextFireTime(a: AlarmConfig, now: LocalDateTime): LocalDateTime? {
    val mask = a.weekdays and 0x7F
    val alarmTime = LocalTime.of(a.hour.coerceIn(0, 23), a.minute.coerceIn(0, 59))

    if (mask == 0) {
        // One-shot: today if not passed, else tomorrow.
        val today = LocalDateTime.of(now.toLocalDate(), alarmTime)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    for (offset in 0L..7L) {
        val date = now.toLocalDate().plusDays(offset)
        val bit = dayOfWeekBit(date.dayOfWeek)
        if ((mask and bit) == 0) continue
        val candidate = LocalDateTime.of(date, alarmTime)
        if (offset == 0L && !candidate.isAfter(now)) continue
        return candidate
    }
    return null
}

private fun dayOfWeekBit(dow: DayOfWeek): Int = when (dow) {
    DayOfWeek.SUNDAY -> Weekday.SUNDAY
    DayOfWeek.MONDAY -> Weekday.MONDAY
    DayOfWeek.TUESDAY -> Weekday.TUESDAY
    DayOfWeek.WEDNESDAY -> Weekday.WEDNESDAY
    DayOfWeek.THURSDAY -> Weekday.THURSDAY
    DayOfWeek.FRIDAY -> Weekday.FRIDAY
    DayOfWeek.SATURDAY -> Weekday.SATURDAY
}

/** Format the next-fire time relative to now: "Today 07:30", "Tomorrow 07:30", "Wed 07:30". */
fun formatNextAlarm(fireAt: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
    val today = now.toLocalDate()
    val fireDate = fireAt.toLocalDate()
    val daysAway = java.time.temporal.ChronoUnit.DAYS.between(today, fireDate).toInt()
    val timeStr = "%02d:%02d".format(fireAt.hour, fireAt.minute)
    return when (daysAway) {
        0 -> "Today $timeStr"
        1 -> "Tomorrow $timeStr"
        in 2..6 -> "${fireDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} $timeStr"
        else -> "$fireDate $timeStr"
    }
}
