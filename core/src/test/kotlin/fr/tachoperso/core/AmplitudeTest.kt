package fr.tachoperso.core

import fr.tachoperso.core.rules.Amplitude
import fr.tachoperso.core.rules.Analysis
import fr.tachoperso.core.rules.Period
import fr.tachoperso.core.rules.Timeline
import fr.tachoperso.core.tacho.ActivityType
import fr.tachoperso.core.tacho.ActivityType.DRIVING
import fr.tachoperso.core.tacho.ActivityType.REST
import fr.tachoperso.core.tacho.ActivityType.WORK
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class AmplitudeTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun analysis(start: LocalDateTime, vararg blocks: Pair<ActivityType, Int>): Analysis {
        var t = start.atZone(paris).toInstant()
        val periods = mutableListOf(Period(t.minusSeconds(48 * 3600), t, REST))
        blocks.forEach { (type, min) ->
            val end = t.plusSeconds(min * 60L)
            periods += Period(t, end, type)
            t = end
        }
        return Analysis(Timeline.merge(periods), paris)
    }

    @Test
    fun `45 min retirees au-dela de 6 h d amplitude`() {
        // 6h00 → 15h45 : 9h45 d'amplitude, 8h de conduite.
        val a = analysis(LocalDateTime.of(2026, 10, 5, 6, 0), DRIVING to 240, REST to 45, DRIVING to 240, WORK to 60, REST to 900)
        val d = Amplitude.months(a).single().days.single()
        assertEquals(585, d.amplitude)
        assertEquals(45, d.deduction)
        assertEquals(540, d.net)
    }

    @Test
    fun `rien retire sur une petite journee`() {
        // 3h de conduite + 1h de travail = 4h d'amplitude.
        val a = analysis(LocalDateTime.of(2026, 10, 5, 6, 0), DRIVING to 180, WORK to 60, REST to 900)
        val d = Amplitude.months(a).single().days.single()
        assertEquals(240, d.amplitude)
        assertEquals(0, d.deduction)
        assertEquals(240, d.net)
    }

    @Test
    fun `conduite de plus de 4h30 meme sous 6 h d amplitude`() {
        val a = analysis(LocalDateTime.of(2026, 10, 5, 6, 0), DRIVING to 275, WORK to 10, REST to 900)
        assertEquals(45, Amplitude.months(a).single().days.single().deduction)
    }

    @Test
    fun `journee de nuit rattachee au mois de prise de poste`() {
        // Prise le 31/10 à 22h, fin le 1/11 à 8h.
        val a = analysis(LocalDateTime.of(2026, 10, 31, 22, 0), DRIVING to 270, REST to 45, DRIVING to 285, REST to 900)
        val months = Amplitude.months(a)
        assertEquals(listOf(YearMonth.of(2026, 10)), months.map { it.month })
        assertEquals(600 - 45L, months.single().net)
    }

    @Test
    fun `recap mensuel`() {
        val a = analysis(
            LocalDateTime.of(2026, 10, 5, 6, 0),
            DRIVING to 240, REST to 45, DRIVING to 240, WORK to 60, REST to 660, // 9h45
            DRIVING to 180, WORK to 60, REST to 900, // 4h
        )
        val m = Amplitude.months(a).single()
        assertEquals(2, m.count)
        assertEquals(585 + 240L, m.gross)
        assertEquals(45, m.deductions)
        assertEquals(780, m.net)
        assertEquals(390, m.average)
        assertEquals(585, m.longest!!.amplitude)
    }
}
