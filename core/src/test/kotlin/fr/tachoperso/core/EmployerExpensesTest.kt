package fr.tachoperso.core

import fr.tachoperso.core.rules.EmployerRates
import fr.tachoperso.core.rules.Rates
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class EmployerExpensesTest {
    private val rates = Rates(LocalDate.of(2026, 1, 1), "Avenant 81", 16.36, 10.07, 9.81, 4.42, 8.87, 52.31, 68.67)

    @Test
    fun `taux etranger tronques au centime`() {
        val e = EmployerRates.from(rates, 18.0)
        assertEquals(19.30, e.dejeuner, 1e-9)
        assertEquals(19.30, e.diner, 1e-9)
        assertEquals(10.46, e.petitDejeuner, 1e-9) // 8,87 × 1,18 = 10,4666
        assertEquals(42.42, e.decoucher, 1e-9) // (52,31 − 16,36) × 1,18 = 42,421
    }

    @Test
    fun `sans majoration taux du protocole`() {
        val e = EmployerRates.from(rates, 0.0)
        assertEquals(16.36, e.dejeuner, 1e-9)
        assertEquals(35.95, e.decoucher, 1e-9)
    }
}

class EmployerOverrideTest {
    @Test
    fun `correction manuelle prioritaire`() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        val t = { d: Int, h: Int -> java.time.LocalDateTime.of(2026, 10, d, h, 0).atZone(zone).toInstant() }
        val a = fr.tachoperso.core.rules.Analysis(
            listOf(
                fr.tachoperso.core.rules.Period(t(1, 0), t(5, 6), fr.tachoperso.core.tacho.ActivityType.REST),
                fr.tachoperso.core.rules.Period(t(5, 6), t(5, 18), fr.tachoperso.core.tacho.ActivityType.DRIVING),
                fr.tachoperso.core.rules.Period(t(5, 18), t(6, 6), fr.tachoperso.core.tacho.ActivityType.REST),
                fr.tachoperso.core.rules.Period(t(6, 6), t(6, 18), fr.tachoperso.core.tacho.ActivityType.DRIVING),
                fr.tachoperso.core.rules.Period(t(6, 18), t(7, 6), fr.tachoperso.core.tacho.ActivityType.REST),
            ),
            zone,
        )
        val rates = listOf(Rates(LocalDate.of(2026, 1, 1), "A81", 16.36, 10.07, 9.81, 4.42, 8.87, 52.31, 68.67))
        val auto = fr.tachoperso.core.rules.EmployerExpenses.compute(a, emptyList(), rates, zone, 18.0)
        assertEquals(2, auto.size)
        assertEquals(true, auto[0].decoucher)
        val o = mapOf(LocalDate.of(2026, 10, 5) to fr.tachoperso.core.rules.DayOverride(diner = false, decoucher = false))
        val fixed = fr.tachoperso.core.rules.EmployerExpenses.compute(a, emptyList(), rates, zone, 18.0, o)
        assertEquals(false, fixed[0].diner)
        assertEquals(false, fixed[0].decoucher)
        assertEquals(true, fixed[0].autoDecoucher)
        assertEquals(true, fixed[0].edited)
        val m = fr.tachoperso.core.rules.EmployerExpenses.months(fixed).single()
        assertEquals(1, m.diners)
        assertEquals(1, m.decouchers)
    }
}
