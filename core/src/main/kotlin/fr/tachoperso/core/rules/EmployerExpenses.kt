package fr.tachoperso.core.rules

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.floor

/**
 * Frais « mode employeur », reconstitué à partir des bulletins : chaque journée de travail donne
 * déjeuner + dîner + petit-déjeuner ; un découcher (sans repas) chaque soir, sauf avant un repos
 * hebdomadaire pris au domicile. Taux = barème du protocole majoré (art. 13 étranger : +18 %),
 * tronqués au centime comme sur les bulletins (8,87 × 1,18 = 10,4666 → 10,46).
 */
data class EmployerRates(val dejeuner: Double, val diner: Double, val petitDejeuner: Double, val decoucher: Double) {
    companion object {
        fun from(r: Rates, markupPercent: Double): EmployerRates {
            val k = 1 + markupPercent / 100
            fun t(x: Double) = floor(x * k * 100 + 1e-6) / 100
            return EmployerRates(t(r.repas), t(r.repas), t(r.casseCroute), t(r.decoucher))
        }
    }
}

data class EmployerDay(val date: LocalDate, val shift: Shift, val rates: EmployerRates, val decoucher: Boolean) {
    val total: Double get() = rates.dejeuner + rates.diner + rates.petitDejeuner + if (decoucher) rates.decoucher else 0.0
}

/** Comptes du mois, dans les rubriques des bulletins. */
data class EmployerMonth(val month: YearMonth, val days: List<EmployerDay>) {
    val dejeuners: Int get() = days.size
    val diners: Int get() = days.size
    val petitsDejeuners: Int get() = days.size
    val decouchers: Int get() = days.count { it.decoucher }
    val total: Double get() = days.sumOf { it.total }
}

/** Rubriques relevées sur le bulletin du mois, pour comparaison. */
data class PayslipCounts(val dejeuners: Int, val diners: Int, val decouchers: Int, val petitsDejeuners: Int)

object EmployerExpenses {
    fun compute(analysis: Analysis, nights: List<Night>, rates: List<Rates>, zone: ZoneId, markupPercent: Double): List<EmployerDay> =
        analysis.shifts.mapNotNull { s ->
            val date = s.start.atZone(zone).toLocalDate()
            val r = Expenses.ratesAt(rates, date) ?: return@mapNotNull null
            val after = s.restAfter
            val night = nights.firstOrNull { it.rest == after }
            // Retour pour le repos hebdomadaire (au domicile, ou à préciser) : pas de découcher.
            val weeklyHome = after != null && after.isWeekly && night?.status != NightStatus.AWAY
            EmployerDay(date, s, EmployerRates.from(r, markupPercent), decoucher = after != null && !weeklyHome)
        }

    fun months(days: List<EmployerDay>): List<EmployerMonth> =
        days.groupBy { YearMonth.from(it.date) }.map { (m, l) -> EmployerMonth(m, l) }.sortedByDescending { it.month }
}
