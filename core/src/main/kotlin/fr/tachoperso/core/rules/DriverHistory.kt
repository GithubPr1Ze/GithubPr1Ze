package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.CardIdentity
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.DailyActivity
import java.time.Instant
import java.time.ZoneId

/**
 * Historique d'un conducteur reconstitué à partir de toutes ses lectures : la carte ne garde
 * qu'une fenêtre glissante, la fusion permet de conserver tout l'historique.
 */
class DriverHistory(
    reports: List<CardReport>,
    val zone: ZoneId = ZoneId.of("Europe/Paris"),
    /** Heure de la dernière lecture : la carte « prolonge » la journée en cours jusqu'à minuit UTC. */
    until: Instant? = null,
) {
    /** Lectures de la plus ancienne à la plus récente (par date du jour le plus récent). */
    private val ordered = reports.sortedBy { it.days.maxOfOrNull { d -> d.date } }

    val identity: CardIdentity? = ordered.lastOrNull { it.identity != null }?.identity
    val latest: CardReport? = ordered.lastOrNull()

    /** Une journée par date ; en cas de doublon, la lecture la plus récente l'emporte. */
    val days: List<DailyActivity> = ordered.flatMap { it.days }
        .associateBy { it.date }.values.sortedBy { it.date }

    val timeline: List<Period> = Timeline.build(days).let { t ->
        if (until == null || t.isEmpty()) t else Timeline.slice(t, t.first().start, until)
    }
    val analysis: Analysis = Analysis(timeline, zone)
    val localDays: List<LocalDay> = LocalDays.build(timeline, days, zone)
}
