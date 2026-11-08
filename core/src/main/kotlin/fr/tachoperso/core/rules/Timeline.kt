package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.ActivityType
import fr.tachoperso.core.tacho.DailyActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Période continue d'une même activité, en temps absolu. */
data class Period(
    val start: Instant,
    val end: Instant,
    val type: ActivityType,
    val cardOut: Boolean = false,
    val crew: Boolean = false,
) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()

    /** Repos au sens de la RSE : seule l'activité « repos » compte (carte insérée ou non). */
    val isRest: Boolean get() = type == ActivityType.REST

    /** Travail au sens du Code des transports (conduite + autres tâches), hors disponibilité. */
    val isWork: Boolean get() = type == ActivityType.DRIVING || type == ActivityType.WORK
}

object Timeline {
    /**
     * Assemble les journées de la carte (en UTC) en une suite continue de périodes.
     * Les jours absents de la carte sont comptés comme repos carte retirée.
     */
    fun build(days: List<DailyActivity>): List<Period> {
        val sorted = days.sortedBy { it.date }
        val raw = mutableListOf<Period>()
        var expected: Instant? = null
        for (day in sorted) {
            val dayStart = day.date.atStartOfDay().toInstant(ZoneOffset.UTC)
            if (expected != null && dayStart > expected) {
                raw += Period(expected, dayStart, ActivityType.REST, cardOut = true)
            }
            val valid = day.changes.filter { it.minute < DailyActivity.MINUTES_PER_DAY }
            valid.forEachIndexed { i, c ->
                val endMinute = if (i + 1 < valid.size) valid[i + 1].minute else DailyActivity.MINUTES_PER_DAY
                if (endMinute > c.minute) {
                    raw += Period(
                        dayStart.plusSeconds(c.minute * 60L), dayStart.plusSeconds(endMinute * 60L),
                        c.type, c.cardNotInserted, crew = c.crew && !c.cardNotInserted,
                    )
                }
            }
            expected = dayStart.plusSeconds(86_400)
        }
        return merge(raw)
    }

    fun merge(periods: List<Period>): List<Period> {
        val out = mutableListOf<Period>()
        for (p in periods) {
            val last = out.lastOrNull()
            if (last != null && last.end == p.start && last.type == p.type && last.cardOut == p.cardOut && last.crew == p.crew) {
                out[out.size - 1] = last.copy(end = p.end)
            } else {
                out += p
            }
        }
        return out
    }

    /** Découpe [periods] sur l'intervalle [from, to[. */
    fun slice(periods: List<Period>, from: Instant, to: Instant): List<Period> =
        periods.mapNotNull { p ->
            val s = maxOf(p.start, from)
            val e = minOf(p.end, to)
            if (e > s) p.copy(start = s, end = e) else null
        }

    fun minutes(periods: List<Period>, type: ActivityType): Long =
        periods.filter { it.type == type }.sumOf { it.minutes }

    /** Minutes de [periods] tombant dans la plage horaire locale [fromHour, toHour[ (peut passer minuit). */
    fun minutesInHours(periods: List<Period>, zone: ZoneId, fromHour: Int, toHour: Int, filter: (Period) -> Boolean): Long {
        var total = 0L
        for (p in periods.filter(filter)) {
            var day: LocalDate = p.start.atZone(zone).toLocalDate().minusDays(1)
            val lastDay = p.end.atZone(zone).toLocalDate()
            while (!day.isAfter(lastDay)) {
                val s = day.atTime(fromHour, 0).atZone(zone).toInstant()
                val endDay = if (toHour <= fromHour) day.plusDays(1) else day
                val e = endDay.atTime(toHour, 0).atZone(zone).toInstant()
                val a = maxOf(s, p.start)
                val b = minOf(e, p.end)
                if (b > a) total += Duration.between(a, b).toMinutes()
                day = day.plusDays(1)
            }
        }
        return total
    }
}

/** Une journée calendaire locale, pour l'affichage. */
data class LocalDay(val date: LocalDate, val periods: List<Period>, val distanceKm: Int) {
    fun minutes(type: ActivityType): Long = Timeline.minutes(periods.filter { !it.cardOut || it.type != ActivityType.REST }, type)
    val minutesCardOut: Long get() = periods.filter { it.cardOut && it.type == ActivityType.REST }.sumOf { it.minutes }
    val restMinutes: Long get() = Timeline.minutes(periods, ActivityType.REST)
    val serviceMinutes: Long
        get() = minutes(ActivityType.DRIVING) + minutes(ActivityType.WORK) + minutes(ActivityType.AVAILABILITY)
    val isWorked: Boolean get() = periods.any { it.type != ActivityType.REST }
}

object LocalDays {
    fun build(timeline: List<Period>, days: List<DailyActivity>, zone: ZoneId): List<LocalDay> {
        if (timeline.isEmpty()) return emptyList()
        val km = days.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.distanceKm } }
        val first = timeline.first().start.atZone(zone).toLocalDate()
        val last = timeline.last().end.minusSeconds(1).atZone(zone).toLocalDate()
        val out = mutableListOf<LocalDay>()
        var d = first
        while (!d.isAfter(last)) {
            val from = d.atStartOfDay(zone).toInstant()
            val to = d.plusDays(1).atStartOfDay(zone).toInstant()
            out += LocalDay(d, Timeline.slice(timeline, from, to), km[d] ?: 0)
            d = d.plusDays(1)
        }
        return out.sortedByDescending { it.date }
    }
}
