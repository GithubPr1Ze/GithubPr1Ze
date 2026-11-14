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
