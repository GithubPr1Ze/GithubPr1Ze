package fr.tachoperso.core

import fr.tachoperso.core.rules.Landmass
import fr.tachoperso.core.rules.LocationEvent
import fr.tachoperso.core.rules.LocationKind
import fr.tachoperso.core.rules.NightStatus
import fr.tachoperso.core.rules.Nights
import fr.tachoperso.core.rules.Period
import fr.tachoperso.core.rules.RestBlock
import fr.tachoperso.core.tacho.ActivityType.DRIVING
import fr.tachoperso.core.tacho.ActivityType.REST
import fr.tachoperso.core.tacho.GeoPoint
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NightsTest {
    private val home = GeoPoint(50.291, 2.777) // Arras (exemple)
    private val kent = GeoPoint(51.288, 0.555)
    private val rotterdam = GeoPoint(51.88, 4.08)
    private val zone = ZoneId.of("Europe/Paris")
    private val t0 = Instant.parse("2026-10-01T00:00:00Z")
    private fun h(x: Long) = t0.plusSeconds(x * 3600)

    @Test
    fun `terres separees par la mer`() {
        assertEquals(Landmass.CONTINENT, Landmass.of(home))
        assertEquals(Landmass.CONTINENT, Landmass.of(GeoPoint(50.72, 1.61))) // Boulogne
        assertEquals(Landmass.CONTINENT, Landmass.of(GeoPoint(50.95, 1.85))) // Calais
        assertEquals(Landmass.GREAT_BRITAIN, Landmass.of(GeoPoint(51.13, 1.31))) // Douvres
        assertEquals(Landmass.GREAT_BRITAIN, kotlin.run { Landmass.of(kent) })
        assertEquals(Landmass.GREAT_BRITAIN, Landmass.of(GeoPoint(52.48, 1.75))) // Lowestoft
        assertEquals(Landmass.CONTINENT, Landmass.of(rotterdam))
    }

    @Test
    fun `classement selon distance et marge`() {
        assertEquals(NightStatus.HOME, Nights.classify(1.0, 0.0))
        assertEquals(NightStatus.AWAY, Nights.classify(100.0, 30.0))
        assertNull(Nights.classify(40.0, 76.0))
        assertNull(Nights.classify(1.0, 50.0))
    }

    @Test
    fun `domicile detecte et nuits classees`() {
        // Deux repos hebdo au domicile (lieu saisi pendant le repos), un repos journalier en Angleterre.
        val timeline = listOf(
            Period(h(0), h(48), REST),
            Period(h(48), h(56), DRIVING),
            Period(h(56), h(67), REST),
            Period(h(67), h(75), DRIVING),
            Period(h(75), h(123), REST),
        )
        val rests = listOf(RestBlock(h(0), h(48), false), RestBlock(h(56), h(67), false), RestBlock(h(75), h(123), false))
        val loc = listOf(
            LocationEvent(h(10), LocationKind.DAY_END, "", "F", home, 5, 1000),
            LocationEvent(h(54), LocationKind.DRIVING_3H, "", null, kent, 5, 1400),
            LocationEvent(h(100), LocationKind.DAY_END, "", "F", GeoPoint(50.292, 2.778), 5, 2000),
        )
        val (nights, clusters, found) = Nights.build(rests, timeline, loc, emptyList(), zone, null, emptyMap())
        assertEquals(1, clusters.size)
        assertEquals(2, clusters[0].weeklyRests)
        assertEquals(home.latitude, found!!.latitude, 0.01)
        assertEquals(listOf(NightStatus.HOME, NightStatus.AWAY, NightStatus.HOME), nights.map { it.status })
        // Correction manuelle prioritaire
        val (fixed, _, _) = Nights.build(rests, timeline, loc, emptyList(), zone, null, mapOf(h(56) to NightStatus.HOME))
        assertEquals(NightStatus.HOME, fixed[1].status)
        assertEquals(NightStatus.AWAY, fixed[1].autoStatus)
    }
}
