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
    /** Domicile déclaré par le conducteur ; sinon détecté à partir des repos hebdomadaires. */
    declaredHome: GeoPoint? = null,
    /** Corrections manuelles des nuits, par heure de début du repos. */
    nightOverrides: Map<Instant, NightStatus> = emptyMap(),
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

    /** Traversées avec durée mesurée ou estimée et temps passé en repos. */
    val ferry: List<FerryCrossing> = Ferry.build(crossings, timeline)

    fun ferryOn(date: LocalDate): List<FerryCrossing> =
        ferry.filter { it.from.atZone(zone).toLocalDate() == date }

    /** Traversées commencées entre [from] et [to] inclus (dates de Paris). */
    fun ferryBetween(from: LocalDate, to: LocalDate): List<FerryCrossing> =
        ferry.filter { val d = it.from.atZone(zone).toLocalDate(); !d.isBefore(from) && !d.isAfter(to) }

    /** Synthèse mensuelle, du mois le plus récent au plus ancien. */
    val ferryMonths: List<FerryMonth> = Ferry.months(ferry, zone)

    /** Pays de début et de fin des lieux saisis ce jour-là (ex. « F → D »), null si aucun. */
    fun countriesOn(date: LocalDate): String? {
        val c = locationsOn(date).mapNotNull { it.country?.ifBlank { null } }
        if (c.isEmpty()) return null
        return if (c.first() == c.last()) c.first() else "${c.first()} → ${c.last()}"
    }

    private val nightData = Nights.build(analysis.restBlocks, timeline, locations, ferry, zone, declaredHome, nightOverrides)

    /** Repos de 9 h et plus, avec lieu et statut domicile / découché. */
    val nights: List<Night> = nightData.first

    /** Zones de repos hebdomadaire, de la plus fréquente à la moins fréquente. */
    val restClusters: List<RestCluster> = nightData.second

    /** Domicile retenu (déclaré ou détecté), null si inconnu. */
    val home: GeoPoint? = nightData.third
    val homeDeclared: Boolean = declaredHome != null

    fun nightsOn(date: LocalDate): List<Night> = nights.filter { it.date == date }

    /** Lieux de coupure connus à 2 km près, du plus fréquenté au moins fréquenté. */
    val stops: List<Stop> = Stops.group(nights)

    val nightMonths: List<NightMonth> = Nights.months(nights)


    /**
     * Journée à l'étranger : repos suivant situé en Grande-Bretagne / Irlande, ou dernier pays
     * saisi (début/fin de journée) avant la fin de ce repos différent de la France.
     */
    fun isAbroad(shift: Shift): Boolean {
        val night = nights.firstOrNull { it.rest == shift.restAfter }
        val p = night?.point
        if (p != null && (night.uncertaintyKm ?: 99.0) <= 30 && Landmass.of(p) != Landmass.CONTINENT) return true
        val until = shift.restAfter?.end ?: shift.end
        val country = locations.lastOrNull { it.country != null && it.country.isNotBlank() && it.time >= shift.start && it.time <= until }?.country
        return country != null && country != "F"
    }

    /** Frais de route par journée de travail ; majoration étranger (art. 13) si [abroadMarkup]. */
    fun expenses(rates: List<Rates>, abroadMarkup: Boolean): List<ShiftExpenses> =
        Expenses.compute(analysis, nights, rates, zone) { abroadMarkup && isAbroad(it) }


    /** Frais selon la pratique de l'employeur (voir [EmployerExpenses]). */
    fun employerExpenses(rates: List<Rates>, markupPercent: Double, overrides: Map<LocalDate, DayOverride> = emptyMap()): List<EmployerDay> =
        EmployerExpenses.compute(analysis, nights, rates, zone, markupPercent, overrides)

    /** Repos dont le statut domicile / découché reste à préciser, du plus récent au plus ancien. */
    val nightsToClarify: List<Night> get() = nights.filter { it.status == null }.sortedByDescending { it.rest.start }

    /** Temps de service (minutes) des jours du mois, heure de Paris. */
    fun monthService(month: java.time.YearMonth): Long =
        localDays.filter { java.time.YearMonth.from(it.date) == month }.sumOf { it.serviceMinutes }

    /** Heures de nuit (21 h – 6 h) des journées de travail commencées dans le mois. */
    fun monthNight(month: java.time.YearMonth): Long =
        analysis.shifts.filter { java.time.YearMonth.from(analysis.localDate(it.start)) == month }.sumOf { analysis.nightMinutes(it.periods) }


    /** Utilisations de véhicules de toutes les lectures, sans doublon. */
    val vehicleUses: List<fr.tachoperso.core.tacho.VehicleUse> = ordered.flatMap { it.vehicleUses }
        .distinctBy { it.key to it.firstUse }.sortedBy { it.firstUse }

    /** Véhicules utilisés, du plus récent au plus ancien. */
    val vehicles: List<Vehicle> = vehicleUses.groupBy { it.key }
        .map { (k, l) -> Vehicle(k, l.first().nation, l.first().registration, l.firstNotNullOfOrNull { it.vin }, l) }
        .sortedByDescending { it.lastUse }

    private fun dayBounds(date: LocalDate) = date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

    /** Véhicules utilisés ce jour-là (heure de Paris). */
    fun vehiclesOn(date: LocalDate): List<Vehicle> {
        val (a, b) = dayBounds(date)
        return vehicles.filter { v -> v.uses.any { it.firstUse < b && (it.lastUse ?: it.firstUse) >= a } }
    }

    /**
     * Société d'un jour : celle du véhicule utilisé le plus longtemps ce jour-là, d'après
     * l'affectation véhicule → société choisie par le conducteur.
     */
    fun companyOn(date: LocalDate, vehicleCompany: Map<String, String>): String? {
        val (a, b) = dayBounds(date)
        return vehicleUses.filter { it.firstUse < b && (it.lastUse ?: it.firstUse) >= a }
            .maxByOrNull { java.time.Duration.between(maxOf(it.firstUse, a), minOf(it.lastUse ?: it.firstUse, b)) }
            ?.let { vehicleCompany[it.key] }
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

/** Véhicule utilisé (regroupement de ses utilisations). */
data class Vehicle(
    val key: String,
    val nation: String,
    val registration: String,
    val vin: String?,
    val uses: List<fr.tachoperso.core.tacho.VehicleUse>,
) {
    val firstUse: Instant get() = uses.minOf { it.firstUse }
    val lastUse: Instant get() = uses.maxOf { it.lastUse ?: it.firstUse }
    val km: Int get() = uses.sumOf { it.km ?: 0 }
    fun days(zone: ZoneId): Int = uses.flatMap { u ->
        generateSequence(u.firstUse.atZone(zone).toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter((u.lastUse ?: u.firstUse).atZone(zone).toLocalDate()) }.toList()
    }.distinct().size
}
