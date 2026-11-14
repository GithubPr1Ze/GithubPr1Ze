package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.ActivityType
import fr.tachoperso.core.tacho.Crossing
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Traversée ferry/train telle qu'affichée : durée mesurée par le chronotachygraphe, ou estimée
 * quand le mode a été coupé aussitôt (activé en conduite, il s'arrête au passage automatique en
 * repos à la coupure du contact) : on retient alors le repos qui commence à ce moment-là.
 */
data class FerryCrossing(
    val raw: Crossing,
    val from: Instant,
    val to: Instant?,
    val estimated: Boolean,
    /** Minutes enregistrées en repos pendant la traversée (hors temps de service). */
    val restMinutes: Long,
) {
    val minutes: Long get() = to?.let { Duration.between(from, it).toMinutes() } ?: 0
    val known: Boolean get() = minutes > 0

    /** Durée réelle du mode ferry/train avant sa coupure, en secondes (traversées estimées). */
    val cutAfterSeconds: Long? get() = if (estimated) raw.end?.let { Duration.between(raw.start, it).seconds } else null
}

/** Synthèse mensuelle des traversées. */
data class FerryMonth(
    val month: YearMonth,
    val crossings: List<FerryCrossing>,
) {
    val count: Int get() = crossings.size
    val minutes: Long get() = crossings.sumOf { it.minutes }
    val restMinutes: Long get() = crossings.sumOf { it.restMinutes }
    val estimatedMinutes: Long get() = crossings.filter { it.estimated }.sumOf { it.minutes }
    val estimatedCount: Int get() = crossings.count { it.estimated }
    val unknownCount: Int get() = crossings.count { !it.known }
}

object Ferry {
    /** Un repos plus long n'est pas retenu comme durée estimée d'une traversée. */
    private const val MAX_ESTIMATE_MINUTES = 12 * 60L

    fun build(crossings: List<Crossing>, timeline: List<Period>): List<FerryCrossing> {
        val out = mutableListOf<FerryCrossing>()
        for (c in crossings.sortedBy { it.start }) {
            // Réactivation pendant une traversée déjà comptée (ex. deuxième essai en fin de repos).
            if (!c.measured && out.any { it.to != null && c.start >= it.from && c.start <= it.to }) continue
            out += if (c.measured) {
                FerryCrossing(c, c.start, c.end, estimated = false, restMinutes = restIn(timeline, c.start, c.end!!))
            } else {
                val rest = restStartingAt(timeline, c.start, c.end ?: c.start)
                if (rest != null) FerryCrossing(c, rest.first, rest.second, estimated = true, restMinutes = Duration.between(rest.first, rest.second).toMinutes())
                else FerryCrossing(c, c.start, null, estimated = false, restMinutes = 0)
            }
        }
        return out
    }

    fun months(list: List<FerryCrossing>, zone: ZoneId): List<FerryMonth> = list
        .groupBy { YearMonth.from(it.from.atZone(zone)) }
        .map { (m, l) -> FerryMonth(m, l.sortedBy { it.from }) }
        .sortedByDescending { it.month }

    private fun restIn(timeline: List<Period>, from: Instant, to: Instant): Long =
        Timeline.minutes(Timeline.slice(timeline, from, to), ActivityType.REST)

    /** Repos (carte insérée) commençant entre l'activation du mode et 2 min après sa coupure. */
    private fun restStartingAt(timeline: List<Period>, start: Instant, cut: Instant): Pair<Instant, Instant>? {
        val lo = start.truncatedTo(ChronoUnit.MINUTES)
        val hi = cut.truncatedTo(ChronoUnit.MINUTES).plusSeconds(120)
        val i = timeline.indexOfFirst { it.isRest && !it.cardOut && it.start >= lo && it.start <= hi }
        if (i < 0) return null
        var end = timeline[i].end
        var j = i + 1
        while (j < timeline.size && timeline[j].isRest && !timeline[j].cardOut && timeline[j].start == end) {
            end = timeline[j].end
            j++
        }
        val from = timeline[i].start
        return if (Duration.between(from, end).toMinutes() in 1..MAX_ESTIMATE_MINUTES) from to end else null
    }
}
