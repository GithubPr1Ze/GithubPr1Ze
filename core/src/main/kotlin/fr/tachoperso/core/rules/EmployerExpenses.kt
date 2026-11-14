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

/** Correction manuelle d'une journée : null = calcul automatique. */
data class DayOverride(
    val dejeuner: Boolean? = null,
    val diner: Boolean? = null,
    val petitDejeuner: Boolean? = null,
    val decoucher: Boolean? = null,
) {
    val isEmpty: Boolean get() = dejeuner == null && diner == null && petitDejeuner == null && decoucher == null
}

data class EmployerDay(
    val date: LocalDate,
    val shift: Shift,
    val rates: EmployerRates,
    val dejeuner: Boolean,
    val diner: Boolean,
    val petitDejeuner: Boolean,
    val decoucher: Boolean,
    /** Découcher calculé automatiquement (avant correction). */
    val autoDecoucher: Boolean,
    val edited: Boolean,
) {
    val total: Double
        get() = (if (dejeuner) rates.dejeuner else 0.0) + (if (diner) rates.diner else 0.0) +
            (if (petitDejeuner) rates.petitDejeuner else 0.0) + (if (decoucher) rates.decoucher else 0.0)
}

/** Comptes du mois, dans les rubriques des bulletins. */
data class EmployerMonth(val month: YearMonth, val days: List<EmployerDay>) {
    val dejeuners: Int get() = days.count { it.dejeuner }
    val diners: Int get() = days.count { it.diner }
    val petitsDejeuners: Int get() = days.count { it.petitDejeuner }
    val decouchers: Int get() = days.count { it.decoucher }
    val total: Double get() = days.sumOf { it.total }
    val edited: Int get() = days.count { it.edited }
}

/** Rubriques relevées sur le bulletin du mois, pour comparaison. */
data class PayslipCounts(val dejeuners: Int, val diners: Int, val decouchers: Int, val petitsDejeuners: Int)

object EmployerExpenses {
    fun compute(
        analysis: Analysis,
        nights: List<Night>,
        rates: List<Rates>,
        zone: ZoneId,
        markupPercent: Double,
        overrides: Map<LocalDate, DayOverride> = emptyMap(),
    ): List<EmployerDay> = analysis.shifts.mapNotNull { s ->
        val date = s.start.atZone(zone).toLocalDate()
        val r = Expenses.ratesAt(rates, date) ?: return@mapNotNull null
        val after = s.restAfter
        val night = nights.firstOrNull { it.rest == after }
        // Retour pour le repos hebdomadaire (au domicile, ou à préciser) : pas de découcher.
        val weeklyHome = after != null && after.isWeekly && night?.status != NightStatus.AWAY
        val auto = after != null && !weeklyHome
        val o = overrides[date]
        EmployerDay(
            date = date,
            shift = s,
            rates = EmployerRates.from(r, markupPercent),
            dejeuner = o?.dejeuner ?: true,
            diner = o?.diner ?: true,
            petitDejeuner = o?.petitDejeuner ?: true,
            decoucher = o?.decoucher ?: auto,
            autoDecoucher = auto,
            edited = o != null && !o.isEmpty,
        )
    }

    fun months(days: List<EmployerDay>): List<EmployerMonth> =
        days.groupBy { YearMonth.from(it.date) }.map { (m, l) -> EmployerMonth(m, l) }.sortedByDescending { it.month }
}
