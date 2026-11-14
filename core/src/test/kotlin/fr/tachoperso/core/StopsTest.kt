package fr.tachoperso.core

import fr.tachoperso.core.rules.Night
import fr.tachoperso.core.rules.RestBlock
import fr.tachoperso.core.rules.Stops
import fr.tachoperso.core.tacho.GeoPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class StopsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val arras = GeoPoint(50.291, 2.777) // exemple
    private val rotterdam = GeoPoint(51.88, 4.08)
    private val kent = GeoPoint(51.288, 0.555)

    /** Repos de 11 h commencé à 18 h UTC le [day] octobre 2026. */
    private fun night(day: Int, point: GeoPoint?, marginKm: Double?): Night {
        val start = Instant.parse("2026-10-%02dT18:00:00Z".format(day))
        return Night(RestBlock(start, start.plusSeconds(11 * 3600), false), start.atZone(zone).toLocalDate(), point, marginKm, null, null, false)
    }

    private val nights = listOf(
        night(1, arras, 0.0),
        night(2, rotterdam, 1.5),
        night(3, GeoPoint(50.296, 2.781), 0.0), // à 600 m du premier
        night(4, kent, 40.0), // trop imprécis
        night(5, null, null), // aucun point
        night(6, GeoPoint(50.289, 2.770), 2.0),
        night(7, GeoPoint(51.95, 4.08), 0.0), // à 8 km de Rotterdam : autre lieu
    )

    @Test
    fun `seuls les repos connus a 2 km pres sont classes`() {
        assertEquals(listOf(true, true, true, false, false, true, true), nights.map(Stops::isExact))
    }

    @Test
    fun `regroupement a 2 km et tri par frequentation`() {
        val stops = Stops.group(nights)
        assertEquals(listOf(3, 1, 1), stops.map { it.count })
        assertEquals(arras, stops[0].anchor)
        assertEquals(LocalDate.of(2026, 10, 6), stops[0].last.date)
        // À fréquentation égale, le lieu vu le plus récemment passe devant.
        assertEquals(LocalDate.of(2026, 10, 7), stops[1].last.date)
        assertEquals(rotterdam, stops[2].anchor)
    }

    @Test
    fun `limitation a une periode`() {
        val stops = Stops.within(Stops.group(nights), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3))
        assertEquals(listOf(1, 1), stops.map { it.count })
        assertEquals(setOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3)), stops.map { it.last.date }.toSet())
    }
}
