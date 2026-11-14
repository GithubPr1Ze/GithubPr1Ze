package fr.tachoperso.core

import fr.tachoperso.core.rules.Ferry
import fr.tachoperso.core.rules.Period
import fr.tachoperso.core.tacho.ActivityType.AVAILABILITY
import fr.tachoperso.core.tacho.ActivityType.DRIVING
import fr.tachoperso.core.tacho.ActivityType.REST
import fr.tachoperso.core.tacho.Crossing
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FerryTest {
    private val t0 = Instant.parse("2026-10-12T07:00:00Z")
    private fun at(min: Long, sec: Long = 0) = t0.plusSeconds(min * 60 + sec)

    // conduite 0–45, repos 45–204 (2h39), conduite 204–240
    private val timeline = listOf(
        Period(at(0), at(45), DRIVING),
        Period(at(45), at(120), REST),
        Period(at(120), at(204), REST),
        Period(at(204), at(240), DRIVING),
    )

    @Test
    fun `traversee coupee au passage en repos estimee sur le repos suivant`() {
        val c = Crossing(at(44, 45), at(44, 59))
        val f = Ferry.build(listOf(c), timeline).single()
        assertTrue(f.estimated)
        assertEquals(at(45), f.from)
        assertEquals(159, f.minutes)
        assertEquals(159, f.restMinutes)
        assertEquals(14, f.cutAfterSeconds)
    }

    @Test
    fun `reactivation pendant la traversee estimee ignoree`() {
        val list = Ferry.build(listOf(Crossing(at(44, 45), at(44, 59)), Crossing(at(203, 9), at(203, 15))), timeline)
        assertEquals(1, list.size)
    }

    @Test
    fun `traversee mesuree avec repos et disponibilite`() {
        val tl = listOf(
            Period(at(0), at(30), DRIVING),
            Period(at(30), at(60), AVAILABILITY),
            Period(at(60), at(150), REST),
            Period(at(150), at(200), DRIVING),
        )
        val f = Ferry.build(listOf(Crossing(at(30), at(150))), tl).single()
        assertFalse(f.estimated)
        assertEquals(120, f.minutes)
        assertEquals(90, f.restMinutes)
        val m = Ferry.months(listOf(f), ZoneId.of("Europe/Paris")).single()
        assertEquals(1, m.count)
        assertEquals(90, m.restMinutes)
    }

    @Test
    fun `sans repos qui suit la duree reste inconnue`() {
        val f = Ferry.build(listOf(Crossing(at(10), at(10, 20))), timeline).single()
        assertFalse(f.known)
    }
}
