package com.reelcounter.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class Stats(
    val today: Int,
    val yesterday: Int,
    val week: Int,
    val total: Int,
    val avgPerActiveDay: Float,
    val daily: List<Pair<String, Int>>,   // last 7 days, oldest -> today
    val hourly: List<Int>,                // 24 buckets, all time
    val peakHour: Int?,
    val sessionsToday: Int,
    val longestSessionToday: Int,
    val creators: List<Pair<String, Int>>
) {
    companion object {
        private const val SESSION_GAP_MS = 5 * 60_000L

        fun compute(db: ReelDb): Stats {
            val zone = ZoneId.systemDefault()
            val todayDate = LocalDate.now(zone)
            val all = db.allTimestamps()

            val perDay = HashMap<LocalDate, Int>()
            val hourly = IntArray(24)
            for (t in all) {
                val z = Instant.ofEpochMilli(t).atZone(zone)
                perDay.merge(z.toLocalDate(), 1, Int::plus)
                hourly[z.hour]++
            }

            val daily = (6 downTo 0).map { back ->
                val d = todayDate.minusDays(back.toLong())
                d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) to (perDay[d] ?: 0)
            }

            val startOfToday = todayDate.atStartOfDay(zone).toInstant().toEpochMilli()
            var sessions = 0
            var longest = 0
            var cur = 0
            var prev = -1L
            for (t in all.filter { it >= startOfToday }) {
                if (prev < 0 || t - prev > SESSION_GAP_MS) {
                    sessions++
                    cur = 1
                } else cur++
                if (cur > longest) longest = cur
                prev = t
            }

            val peak = hourly.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index

            return Stats(
                today = perDay[todayDate] ?: 0,
                yesterday = perDay[todayDate.minusDays(1)] ?: 0,
                week = daily.sumOf { it.second },
                total = all.size,
                avgPerActiveDay = if (perDay.isEmpty()) 0f else all.size / perDay.size.toFloat(),
                daily = daily,
                hourly = hourly.toList(),
                peakHour = peak,
                sessionsToday = sessions,
                longestSessionToday = longest,
                creators = db.topAuthors(5)
            )
        }
    }
}
