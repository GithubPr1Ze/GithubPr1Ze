package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.ActivityType
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Ligne hebdomadaire du récapitulatif (semaine du lundi au dimanche, RSE). */
data class WeekLine(val monday: LocalDate, val driving: Long, val service: Long) {
    val over35: Long get() = (service - Limits.LEGAL_WEEKLY).coerceAtLeast(0)
    val over43: Long get() = (service - Limits.EQUIVALENCE_WEEKLY).coerceAtLeast(0)
}

/** Ligne journalière : activités du jour (heure de Paris) et frais de la journée de travail commencée ce jour-là. */
data class DayLine(
    val date: LocalDate,
    val driving: Long,
    val work: Long,
    val availability: Long,
    val km: Int,
    val away: Boolean,
    val expenses: Double,
    val expenseText: String,
)

/** Récapitulatif mensuel : heures, frais, découchés, ferry, constats. Base du rapport PDF. */
data class MonthlyReport(
    val month: YearMonth,
    val days: List<DayLine>,
    val weeks: List<WeekLine>,
    val nightMinutes: Long,
    val expenses: ExpenseMonth?,
    val decouchers: Int,
    val nightsToClarify: Int,
    val ferry: FerryMonth?,
    val findings: List<Finding>,
) {
    val driving: Long get() = days.sumOf { it.driving }
    val work: Long get() = days.sumOf { it.work }
    val availability: Long get() = days.sumOf { it.availability }
    val service: Long get() = driving + work + availability
    val workedDays: Int get() = days.count { it.driving + it.work + it.availability > 0 }
    val km: Int get() = days.sumOf { it.km }

    companion object {
        fun build(history: DriverHistory, expenses: List<ShiftExpenses>, month: YearMonth): MonthlyReport {
            val a = history.analysis
            val inMonth = { d: LocalDate -> YearMonth.from(d) == month }
            val byDate = expenses.groupBy { it.date }
            val days = history.localDays.filter { inMonth(it.date) }.sortedBy { it.date }.map { d ->
                val ex = byDate[d.date].orEmpty()
                DayLine(
                    date = d.date,
                    driving = d.minutes(ActivityType.DRIVING),
                    work = d.minutes(ActivityType.WORK),
                    availability = d.minutes(ActivityType.AVAILABILITY),
                    km = d.distanceKm,
                    away = history.nightsOn(d.date).any { it.status == NightStatus.AWAY },
                    expenses = ex.sumOf { it.total },
                    expenseText = ex.flatMap { e -> e.items.map { (if (it.count > 1) "${it.count}× " else "") + it.kind.label.substringBefore(" (") } }
                        .joinToString(", "),
                )
            }
            val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val weeks = generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(month.atEndOfMonth()) }
                .map { WeekLine(it, a.weekDriving(it), a.weekService(it)) }.toList()
            val night = a.shifts.filter { inMonth(a.localDate(it.start)) }.sumOf { a.nightMinutes(it.periods) }
            val nights = history.nights.filter { inMonth(it.date) }
            return MonthlyReport(
                month = month,
                days = days,
                weeks = weeks,
                nightMinutes = night,
                expenses = Expenses.months(expenses).firstOrNull { it.month == month },
                decouchers = nights.count { it.status == NightStatus.AWAY },
                nightsToClarify = nights.count { it.status == null },
                ferry = history.ferryMonths.firstOrNull { it.month == month },
                findings = a.findings.filter { inMonth(a.localDate(it.at)) },
            )
        }
    }
}
