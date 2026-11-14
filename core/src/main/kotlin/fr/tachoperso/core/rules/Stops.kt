package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.GeoPoint
import java.time.LocalDate

/**
 * Lieu de coupure : repos de 9 h et plus situés à moins de 2 km les uns des autres. Le camion ne
 * bouge pas pendant le repos, c'est donc aussi le lieu de départ de la journée suivante.
 */
data class Stop(val center: GeoPoint, val nights: List<Night>) {
    val count: Int get() = nights.size
    val weekly: Int get() = nights.count { it.weekly }
    val last: Night get() = nights.maxBy { it.rest.start }

    /** Point de la première coupure : stable d'une lecture à l'autre, utilisé pour nommer le lieu. */
    val anchor: GeoPoint get() = nights.minBy { it.rest.start }.point ?: center
}

object Stops {
    /**
     * Un repos n'entre dans le classement que si son lieu est connu à 2 km près : au-delà, la
     * carte ne dit pas où le camion s'est arrêté (un point toutes les 3 h de conduite seulement).
     */
    fun isExact(n: Night): Boolean = n.point != null && (n.uncertaintyKm ?: Double.MAX_VALUE) <= Nights.RADIUS_KM

    /**
     * Regroupement glouton dans l'ordre chronologique : un repos rejoint le premier lieu dont le
     * centre est à moins de 2 km. Lieux triés du plus fréquenté au moins fréquenté.
     */
    fun group(nights: List<Night>): List<Stop> {
        val groups = mutableListOf<MutableList<Night>>()
        for (n in nights.filter(::isExact).sortedBy { it.rest.start }) {
            val g = groups.firstOrNull { Nights.distanceKm(center(it), n.point!!) <= Nights.RADIUS_KM }
            if (g != null) g += n else groups += mutableListOf(n)
        }
        return sorted(groups.map { Stop(center(it), it) })
    }

    /** Les mêmes lieux, limités aux repos commencés entre [from] et [to] inclus (dates de Paris). */
    fun within(stops: List<Stop>, from: LocalDate, to: LocalDate): List<Stop> = sorted(
        stops.map { s -> s.copy(nights = s.nights.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }) }
            .filter { it.nights.isNotEmpty() },
    )

    private fun sorted(l: List<Stop>) = l.sortedWith(compareByDescending<Stop> { it.count }.thenByDescending { it.last.rest.start })

    private fun center(l: List<Night>) =
        GeoPoint(l.sumOf { it.point!!.latitude } / l.size, l.sumOf { it.point!!.longitude } / l.size)
}
