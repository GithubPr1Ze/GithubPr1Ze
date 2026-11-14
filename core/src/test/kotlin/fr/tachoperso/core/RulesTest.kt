package fr.tachoperso.core

import fr.tachoperso.core.rules.Analysis
import fr.tachoperso.core.rules.Period
import fr.tachoperso.core.rules.Rule
import fr.tachoperso.core.rules.Severity
import fr.tachoperso.core.rules.Timeline
import fr.tachoperso.core.tacho.ActivityType
import fr.tachoperso.core.tacho.ActivityType.AVAILABILITY
import fr.tachoperso.core.tacho.ActivityType.DRIVING
import fr.tachoperso.core.tacho.ActivityType.REST
import fr.tachoperso.core.tacho.ActivityType.WORK
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

/** Construit une chronologie à partir d'un instant, bloc après bloc. */
private class Tl(start: Instant) {
    var t: Instant = start
    val periods = mutableListOf<Period>()

    fun add(type: ActivityType, hours: Int, minutes: Int = 0): Tl {
        val end = t.plusSeconds((hours * 60L + minutes) * 60)
        periods += Period(t, end, type)
        t = end
        return this
    }

    /** Journée conforme de 24 h : 4h conduite, 45 min, 4h conduite, 1h travail, repos. */
    fun normalDay(): Tl = add(DRIVING, 4).add(REST, 0, 45).add(DRIVING, 4).add(WORK, 1).add(REST, 14, 15)

    fun analysis() = Analysis(Timeline.merge(periods), PARIS)
}

/** Lundi 2 novembre 2026, 6h00 heure de Paris, précédé d'un repos de 48 h. */
private fun week(): Tl {
    val monday6h = LocalDate.of(2026, 11, 2).atTime(6, 0).atZone(PARIS).toInstant()
    return Tl(monday6h.minusSeconds(48 * 3600)).add(REST, 48)
}

private fun Analysis.rules(severity: Severity = Severity.INFRACTION) = findings.filter { it.severity == severity }.map { it.rule }

class RulesTest {
    @Test
    fun `semaine conforme sans aucune infraction`() {
        val tl = week()
        repeat(5) { tl.normalDay() }
        tl.add(REST, 50)
        val a = tl.analysis()
        assertEquals(emptyList(), a.findings, a.findings.joinToString("\n"))
        assertEquals(5, a.shifts.size)
        assertEquals(40 * 60L, a.weekDriving(LocalDate.of(2026, 11, 2)))
        // 56 h − 40 h cette semaine ; la suivante est bornée par les 90 h sur deux semaines.
        assertEquals(16 * 60L, a.weekDrivingLeft(LocalDate.of(2026, 11, 2)))
        assertEquals(50 * 60L, a.nextWeekDrivingMax(LocalDate.of(2026, 11, 2)))
        // Semaine suivante vide : il reste 90 h − 40 h, sous le plafond de 56 h.
        assertEquals(50 * 60L, a.weekDrivingLeft(LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `pause de conduite`() {
        assertTrue(Rule.BREAK in week().add(DRIVING, 4, 31).add(REST, 12).analysis().rules())
        // 15 puis 30 : conforme.
        val split = week().add(DRIVING, 2).add(REST, 0, 15).add(DRIVING, 2).add(REST, 0, 30).add(DRIVING, 4).add(REST, 13)
        assertTrue(Rule.BREAK !in split.analysis().rules())
        // 30 puis 15 : non conforme.
        val wrong = week().add(DRIVING, 2).add(REST, 0, 30).add(DRIVING, 2).add(REST, 0, 15).add(DRIVING, 1).add(REST, 13)
        assertTrue(Rule.BREAK in wrong.analysis().rules())
        // La disponibilité n'est pas une pause.
        val dispo = week().add(DRIVING, 3).add(AVAILABILITY, 1).add(DRIVING, 2).add(REST, 13)
        assertTrue(Rule.BREAK in dispo.analysis().rules())
    }

    @Test
    fun `conduite journaliere 9h et 10h`() {
        val over = week().add(DRIVING, 4, 30).add(REST, 0, 45).add(DRIVING, 4, 30).add(REST, 0, 45).add(DRIVING, 1, 1).add(REST, 12)
        assertTrue(Rule.DAILY_DRIVING in over.analysis().rules())

        val tl = week()
        repeat(3) { tl.add(DRIVING, 4, 30).add(REST, 0, 45).add(DRIVING, 4, 30).add(REST, 0, 45).add(DRIVING, 0, 30).add(REST, 12, 45) }
        val findings = tl.add(REST, 50).analysis().findings.filter { it.rule == Rule.DAILY_DRIVING }
        assertEquals(1, findings.size, "seule la 3e journée à plus de 9 h est en infraction")
    }

    @Test
    fun `repos journalier insuffisant dans les 24 h`() {
        val a = week().add(DRIVING, 4).add(REST, 0, 45).add(DRIVING, 4).add(WORK, 7, 15).add(REST, 8).add(DRIVING, 2).add(REST, 30).analysis()
        assertTrue(Rule.DAILY_REST in a.rules(), a.findings.joinToString("\n"))
    }

    @Test
    fun `quatre repos journaliers reduits`() {
        val tl = week()
        repeat(5) { tl.add(DRIVING, 4).add(REST, 0, 45).add(DRIVING, 4).add(WORK, 6, 15).add(REST, 9) }
        val a = tl.add(REST, 50).analysis()
        assertEquals(1, a.findings.count { it.rule == Rule.REDUCED_DAILY_RESTS }, a.findings.joinToString("\n"))
    }

    @Test
    fun `conduite hebdomadaire et repos hebdomadaire tardif`() {
        val tl = week()
        repeat(6) { tl.add(DRIVING, 4, 30).add(REST, 0, 45).add(DRIVING, 4, 30).add(REST, 14, 15) }
        val a = tl.add(REST, 50).analysis()
        val rules = a.rules()
        assertTrue(Rule.WEEKLY_DRIVING !in rules, "6 x 9h = 54h : conforme")
        assertTrue(Rule.WEEKLY_REST_DELAY !in rules)

        val tl2 = week()
        repeat(7) { tl2.add(DRIVING, 4, 30).add(REST, 0, 45).add(DRIVING, 4, 30).add(REST, 14, 15) }
        val rules2 = tl2.add(REST, 50).analysis().rules()
        assertTrue(Rule.WEEKLY_DRIVING in rules2)
        assertTrue(Rule.WEEKLY_REST_DELAY in rules2)
    }

    @Test
    fun `repos hebdomadaire reduit et compensation`() {
        val tl = week()
        repeat(5) { tl.normalDay() }
        tl.add(REST, 10) // bloc de 24h15 : repos réduit, manque 20h45
        repeat(5) { tl.normalDay() }
        tl.add(REST, 64) // compensation rattachée au repos normal suivant
        repeat(2) { tl.normalDay() }
        val a = tl.analysis()
        assertTrue(a.findings.none { it.rule == Rule.WEEKLY_REST_COMPENSATION }, a.findings.joinToString("\n"))

        val tl2 = week()
        repeat(5) { tl2.normalDay() }
        tl2.add(REST, 10)
        repeat(4) {
            repeat(5) { tl2.normalDay() }
            tl2.add(REST, 45)
        }
        repeat(2) { tl2.normalDay() }
        val comp = tl2.analysis().findings.filter { it.rule == Rule.WEEKLY_REST_COMPENSATION }
        assertEquals(listOf(Severity.INFRACTION), comp.map { it.severity })
    }

    @Test
    fun `deux repos hebdomadaires reduits consecutifs`() {
        val tl = week()
        repeat(5) { tl.normalDay() }
        tl.add(REST, 30)
        repeat(5) { tl.normalDay() }
        tl.add(REST, 30)
        repeat(5) { tl.normalDay() }
        tl.add(REST, 70)
        val a = tl.analysis()
        assertTrue(Rule.WEEKLY_REST in a.rules(Severity.WARNING), a.findings.joinToString("\n"))
    }

    @Test
    fun `code des transports service travail de nuit et pauses`() {
        val service = week().add(DRIVING, 4).add(REST, 0, 45).add(DRIVING, 4).add(AVAILABILITY, 4).add(WORK, 0, 30).add(REST, 12).analysis()
        assertTrue(Rule.SERVICE_DAILY in service.rules())

        // Prise de service à 2h du matin (heure de Paris) : 10h30 de travail.
        val start = LocalDate.of(2026, 11, 2).atTime(2, 0).atZone(PARIS).toInstant()
        val night = Tl(start.minusSeconds(48 * 3600)).add(REST, 48)
            .add(DRIVING, 4, 30).add(REST, 0, 45).add(DRIVING, 4).add(WORK, 2).add(REST, 13).analysis()
        assertTrue(Rule.NIGHT_WORK in night.rules(), night.findings.joinToString("\n"))

        // 6h15 de travail avec une seule pause de 20 min : il faut 30 min.
        val pause = week().add(DRIVING, 3).add(REST, 0, 20).add(WORK, 3, 15).add(REST, 14).analysis()
        assertTrue(Rule.WORK_BREAK in pause.rules())
    }

    @Test
    fun `decoupage en journees locales et heures de nuit`() {
        val a = week().normalDay().add(REST, 30).analysis()
        val shift = a.shifts.single()
        assertEquals(8 * 60L, shift.driving)
        assertEquals(9 * 60L + 45, shift.amplitude)
        // 6h00 → 15h45 : aucune minute entre 21 h et 6 h.
        assertEquals(0L, a.nightMinutes(shift.periods))
    }
}
