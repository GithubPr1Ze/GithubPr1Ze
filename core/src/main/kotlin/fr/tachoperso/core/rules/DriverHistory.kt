package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.CardIdentity
import fr.tachoperso.core.tacho.Crossing
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.DailyActivity
import fr.tachoperso.core.tacho.GeoPoint
import fr.tachoperso.core.tacho.GnssDrivingRecord
import fr.tachoperso.core.tacho.PlaceRecord
import fr.tachoperso.core.tacho.SpecificCondition
import fr.tachoperso.core.tacho.SpecificConditionsParser
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    /** Lieux de début/fin de journée de toutes les lectures, sans doublon. */
    val places: List<PlaceRecord> = ordered.flatMap { it.places }
        .distinctBy { it.time to it.type }.sortedBy { it.time }

    /** Positions « 3 h de conduite » de toutes les lectures, sans doublon. */
    val gnssDriving: List<GnssDrivingRecord> = ordered.flatMap { it.gnssDriving }
        .distinctBy { it.time }.sortedBy { it.time }

    /** Tous les points de localisation, dans l'ordre chronologique. */
    val locations: List<LocationEvent> = (
        places.map { p ->
            LocationEvent(
                p.time,
                if (p.isBegin) LocationKind.DAY_START else LocationKind.DAY_END,
                p.type?.label ?: "Lieu (type inconnu)",
                p.country,
                p.gnss?.point,
                p.gnss?.accuracyM,
                p.odometerKm,
            )
        } + gnssDriving.map { g ->
            LocationEvent(g.time, LocationKind.DRIVING_3H, "Position (3 h de conduite)", null, g.gnss?.point, g.gnss?.accuracyM, g.odometerKm)
        }
    ).sortedBy { it.time }

    fun locationsOn(date: LocalDate): List<LocationEvent> =
        locations.filter { it.time.atZone(zone).toLocalDate() == date }

    /** Conditions particulières de toutes les lectures, sans doublon. */
    val specificConditions: List<SpecificCondition> = ordered.flatMap { it.specificConditions }.distinct()

    /** Traversées ferry/train, de la plus ancienne à la plus récente. */
    val crossings: List<Crossing> = SpecificConditionsParser.crossings(specificConditions)

    fun crossingsOn(date: LocalDate): List<Crossing> =
        crossings.filter { it.start.atZone(zone).toLocalDate() == date }

    /** Traversées commencées entre [from] et [to] inclus (dates de Paris). */
    fun crossingsBetween(from: LocalDate, to: LocalDate): List<Crossing> =
        crossings.filter { val d = it.start.atZone(zone).toLocalDate(); !d.isBefore(from) && !d.isAfter(to) }

    /** Récapitulatif mensuel des traversées, du mois le plus récent au plus ancien. */
    val crossingMonths: List<CrossingMonth> = crossings
        .groupBy { YearMonth.from(it.start.atZone(zone)) }
        .map { (m, l) -> CrossingMonth(m, l.size, l.count { !it.measured }, l.sumOf { it.minutes }) }
        .sortedByDescending { it.month }

    /** Pays de début et de fin des lieux saisis ce jour-là (ex. « F → D »), null si aucun. */
    fun countriesOn(date: LocalDate): String? {
        val c = locationsOn(date).mapNotNull { it.country?.ifBlank { null } }
        if (c.isEmpty()) return null
        return if (c.first() == c.last()) c.first() else "${c.first()} → ${c.last()}"
    }
}

enum class LocationKind { DAY_START, DAY_END, DRIVING_3H }

/** Un point de localisation affichable : lieu saisi ou position automatique. */
data class LocationEvent(
    val time: Instant,
    val kind: LocationKind,
    val label: String,
    /** Pays saisi (lieux de début/fin uniquement). */
    val country: String?,
    val point: GeoPoint?,
    val accuracyM: Int?,
    val odometerKm: Int,
)

/** Traversées d'un mois : nombre, dont durée non enregistrée, et temps total. */
data class CrossingMonth(val month: YearMonth, val count: Int, val unmeasured: Int, val minutes: Long)
