package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.GeoPoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Lieu retenu pour un repos : point, marge (km) et terre ferme certaine (null si traversée entre-temps). */
data class Located(val point: GeoPoint, val uncertaintyKm: Double, val landmass: Landmass?)

/** Terres séparées par la mer : on ne passe de l'une à l'autre que par ferry ou train. */
enum class Landmass {
    CONTINENT, GREAT_BRITAIN, IRELAND;

    companion object {
        /** Découpage grossier, suffisant de part et d'autre de la Manche et de la mer du Nord. */
        fun of(p: GeoPoint): Landmass {
            val lat = p.latitude
            val lon = p.longitude
            return when {
                lon < -5.4 && lat in 51.3..55.4 -> IRELAND
                lat > 61 -> CONTINENT
                lon < 1.5 && lat > 50.5 && (lon < 1.0 || lat > 50.9) -> GREAT_BRITAIN
                lon < 1.8 && lat > 51.3 && lon > -6.5 -> GREAT_BRITAIN
                lon < -2 && lon > -5.8 && lat > 49.8 -> GREAT_BRITAIN
                else -> CONTINENT
            }
        }
    }
}

enum class NightStatus(val label: String) { HOME("Domicile"), AWAY("Découché") }

/**
 * Repos journalier ou hebdomadaire (≥ 9 h) et son lieu. Le statut « découché » est calculé par
 * comparaison avec le domicile ; le conducteur peut le corriger, la correction prime.
 */
data class Night(
    val rest: RestBlock,
    /** Date (heure de Paris) du début du repos. */
    val date: LocalDate,
    val point: GeoPoint?,
    /** Marge sur le lieu : conduite entre le point connu et le repos × 90 km/h (0 = point pris pendant le repos). */
    val uncertaintyKm: Double?,
    val autoStatus: NightStatus?,
    val override: NightStatus?,
    /** Une traversée ferry/train chevauche ce repos. */
    val onFerry: Boolean,
) {
    val status: NightStatus? get() = override ?: autoStatus
    val weekly: Boolean get() = rest.isWeekly
}

/** Zone (rayon 2 km) regroupant des repos hebdomadaires. */
data class RestCluster(val center: GeoPoint, val weeklyRests: Int)

data class NightMonth(val month: YearMonth, val away: Int, val home: Int, val unknown: Int)

object Nights {
    const val RADIUS_KM = 2.0

    /** Vitesse maximale d'un poids lourd, pour borner la distance parcourue entre un point et le repos. */
    private const val KM_PER_DRIVING_MINUTE = 1.5

    /** Marge appliquée à la vitesse moyenne mesurée au compteur. */
    private const val SPEED_MARGIN = 1.2

    fun build(
        restBlocks: List<RestBlock>,
        timeline: List<Period>,
        locations: List<LocationEvent>,
        ferry: List<FerryCrossing>,
        zone: ZoneId,
        declaredHome: GeoPoint?,
        overrides: Map<Instant, NightStatus>,
    ): Triple<List<Night>, List<RestCluster>, GeoPoint?> {
        val rests = restBlocks.filter { it.minutes >= Limits.DAILY_REST_REDUCED }
        val withPoint = locations.filter { it.point != null }
        val placed = rests.map { r -> r to locate(r, withPoint, timeline, ferry) }
        // Zones de domicile : uniquement les repos hebdomadaires localisés à moins de 2 km près.
        val clusters = cluster(placed.filter { it.first.isWeekly && (it.second?.uncertaintyKm ?: 99.0) <= RADIUS_KM }.map { it.second!!.point })
        val home = declaredHome ?: clusters.firstOrNull { it.weeklyRests >= 2 }?.center
        val nights = placed.map { (r, loc) ->
            val p = loc?.point
            val unc = loc?.uncertaintyKm
            val auto = when {
                p == null || unc == null || home == null -> null
                loc.landmass != null && loc.landmass != Landmass.of(home) -> NightStatus.AWAY
                else -> classify(distanceKm(p, home), unc)
            }
            Night(
                rest = r,
                date = r.start.atZone(zone).toLocalDate(),
                point = p,
                uncertaintyKm = unc,
                autoStatus = auto,
                override = overrides[r.start],
                onFerry = ferry.any { f -> f.to != null && f.from < r.end && f.to > r.start },
            )
        }
        return Triple(nights, clusters, home)
    }

    fun months(nights: List<Night>): List<NightMonth> = nights
        .groupBy { YearMonth.from(it.date) }
        .map { (m, l) ->
            NightMonth(m, l.count { it.status == NightStatus.AWAY }, l.count { it.status == NightStatus.HOME }, l.count { it.status == null })
        }
        .sortedByDescending { it.month }

    /**
     * Domicile si le lieu est à moins de 2 km (marge comprise), découché s'il en est forcément
     * plus loin ; sinon indéterminé (à corriger à la main).
     */
    fun classify(distance: Double, uncertainty: Double): NightStatus? = when {
        distance - uncertainty > RADIUS_KM -> NightStatus.AWAY
        uncertainty <= RADIUS_KM && distance <= RADIUS_KM + uncertainty -> NightStatus.HOME
        else -> null
    }

    /**
     * Le camion ne bouge pas pendant le repos : on retient le point connu (lieu saisi ou position
     * GNSS) séparé du repos par le moins de conduite, dans les 12 h avant ou après.
     */
    private fun locate(r: RestBlock, withPoint: List<LocationEvent>, timeline: List<Period>, crossings: List<FerryCrossing>): Located? {
        val h12 = Duration.ofHours(12)
        fun driving(a: Instant, b: Instant) =
            Timeline.minutes(Timeline.slice(timeline, a, b), fr.tachoperso.core.tacho.ActivityType.DRIVING)
        val cands = withPoint
            .filter { it.time >= r.start.minus(h12) && it.time <= r.end.plus(h12) }
            .map { e ->
                val minutes = when {
                    e.time < r.start -> driving(e.time, r.start)
                    e.time > r.end -> driving(r.end, e.time)
                    else -> 0L
                }
                val gap = if (e.time < r.start) Duration.between(e.time, r.start) else if (e.time > r.end) Duration.between(r.end, e.time) else Duration.ZERO
                Triple(e, minutes, gap)
            }
        val best = cands.minWithOrNull(compareBy({ it.second }, { it.third })) ?: return null
        // Sans traversée entre le point et le repos, le camion est resté sur la même terre.
        val t = best.first.time
        val crossed = crossings.any { c ->
            val ct = c.raw.start
            if (t < r.start) ct >= t && ct <= r.start else ct >= r.end && ct <= t
        }
        val land = if (crossed) null else Landmass.of(best.first.point!!)
        fun res(km: Double) = Located(best.first.point!!, km, land)
        if (best.second == 0L) return res(0.0)
        // Vitesse réelle entre le dernier point avant et le premier point après le repos (compteur).
        val before = cands.filter { it.first.time < r.start }.minByOrNull { it.second }
        val after = cands.filter { it.first.time > r.end }.minByOrNull { it.second }
        var kmPerMinute = KM_PER_DRIVING_MINUTE
        if (before != null && after != null) {
            val km = after.first.odometerKm - before.first.odometerKm
            val minutes = before.second + after.second
            if (km in 1..1500 && minutes > 0) {
                kmPerMinute = minOf(KM_PER_DRIVING_MINUTE, km.toDouble() / minutes * SPEED_MARGIN)
                return res(minOf(best.second * kmPerMinute, km.toDouble()))
            }
        }
        return res(best.second * kmPerMinute)
    }

    /** Regroupement glouton : un point rejoint la première zone dont le centre est à moins de 2 km. */
    fun cluster(points: List<GeoPoint>): List<RestCluster> {
        val groups = mutableListOf<MutableList<GeoPoint>>()
        for (p in points) {
            val g = groups.firstOrNull { distanceKm(center(it), p) <= RADIUS_KM }
            if (g != null) g += p else groups += mutableListOf(p)
        }
        return groups.map { RestCluster(center(it), it.size) }.sortedByDescending { it.weeklyRests }
    }

    private fun center(l: List<GeoPoint>) = GeoPoint(l.sumOf { it.latitude } / l.size, l.sumOf { it.longitude } / l.size)

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
