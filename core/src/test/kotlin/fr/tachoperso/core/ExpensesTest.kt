package fr.tachoperso.core

import fr.tachoperso.core.rules.Analysis
import fr.tachoperso.core.rules.ExpenseKind
import fr.tachoperso.core.rules.Expenses
import fr.tachoperso.core.rules.Night
import fr.tachoperso.core.rules.NightStatus
import fr.tachoperso.core.rules.Period
import fr.tachoperso.core.rules.Rates
import fr.tachoperso.core.tacho.ActivityType.DRIVING
import fr.tachoperso.core.tacho.ActivityType.REST
import fr.tachoperso.core.tacho.ActivityType.WORK
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpensesTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val rates = Rates(LocalDate.of(2026, 1, 1), "Avenant 81", 16.36, 10.07, 9.81, 4.42, 8.87, 52.31, 68.67)
    private fun t(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant()

    /** Journée de travail [start, end] entourée de longs repos. */
    private fun analysis(vararg p: Period) = Analysis(listOf(Period(t(1, 0), p.first().start, REST)) + p + Period(p.last().end, t(9, 0), REST), zone)

    private fun night(a: Analysis, status: NightStatus?) =
        Night(a.shifts[0].restAfter!!, LocalDate.of(2026, 10, 5), null, null, status, null, false)

    @Test
    fun `repas couverts entierement par l amplitude`() {
        val a = analysis(Period(t(5, 6), t(5, 14, 15), DRIVING)) // couvre 11h45–14h15 pile
        assertEquals(1, Expenses.mealsCovered(a.shifts[0], zone))
        val b = analysis(Period(t(5, 6), t(5, 14, 0), DRIVING)) // s'arrête avant 14h15
        assertEquals(0, Expenses.mealsCovered(b.shifts[0], zone))
        val c = analysis(Period(t(5, 6), t(5, 21, 30), DRIVING))
        assertEquals(2, Expenses.mealsCovered(c.shifts[0], zone))
    }

    @Test
    fun `retour au domicile repas et casse-croute`() {
        val a = analysis(Period(t(5, 4, 30), t(5, 15), DRIVING))
        val e = Expenses.compute(a, listOf(night(a, NightStatus.HOME)), listOf(rates), zone).single()
        assertEquals(listOf(ExpenseKind.MEAL, ExpenseKind.CASSE_CROUTE), e.items.map { it.kind })
        assertEquals(16.36 + 8.87, e.total, 0.001)
    }

    @Test
    fun `decouche deux repas sans casse-croute`() {
        val a = analysis(Period(t(5, 4, 30), t(5, 21, 30), DRIVING))
        val e = Expenses.compute(a, listOf(night(a, NightStatus.AWAY)), listOf(rates), zone).single()
        assertEquals(listOf(ExpenseKind.GRAND_DEPLACEMENT_2), e.items.map { it.kind })
        assertEquals(68.67, e.total, 0.001)
        val abroad = Expenses.compute(a, listOf(night(a, NightStatus.AWAY)), listOf(rates), zone) { true }.single()
        assertEquals(81.03, abroad.total, 0.001)
    }

    @Test
    fun `nuit seule 4 h de travail entre 22 h et 7 h`() {
        val a = analysis(Period(t(5, 22), t(6, 3), WORK))
        val e = Expenses.compute(a, listOf(night(a, NightStatus.HOME)), listOf(rates), zone).single()
        assertEquals(listOf(ExpenseKind.NIGHT), e.items.map { it.kind })
        assertEquals(9.81, e.total, 0.001)
    }

    @Test
    fun `bareme applicable a la date`() {
        val r2027 = rates.copy(effective = LocalDate.of(2027, 1, 1), repas = 16.85)
        assertEquals(16.36, Expenses.ratesAt(listOf(rates, r2027), LocalDate.of(2026, 12, 31))!!.repas)
        assertEquals(16.85, Expenses.ratesAt(listOf(rates, r2027), LocalDate.of(2027, 1, 1))!!.repas)
    }
}
