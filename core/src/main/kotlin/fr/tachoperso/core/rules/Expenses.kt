package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.ActivityType
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Taux du protocole du 30 avril 1974 (IDCC 16) en vigueur à partir de [effective].
 * Source des montants : barème public (avenants signés et étendus).
 */
data class Rates(
    val effective: LocalDate,
    val source: String,
    val repas: Double,
    val repasUnique: Double,
    val repasUniqueNuit: Double,
    val indemniteSpeciale: Double,
    val casseCroute: Double,
    val grandDeplacement1Repas: Double,
    val grandDeplacement2Repas: Double,
) {
    /** Indemnité de découcher seule, déduite du barème (grand déplacement 1 repas − 1 repas). */
    val decoucher: Double get() = grandDeplacement1Repas - repas
}

enum class ExpenseKind(val label: String, val article: String) {
    MEAL("Repas", "Protocole art. 3"),
    GRAND_DEPLACEMENT_1("Grand déplacement (1 repas + découcher)", "Protocole art. 6"),
    GRAND_DEPLACEMENT_2("Grand déplacement (2 repas + découcher)", "Protocole art. 6"),
    DECOUCHER_ONLY("Découcher seul (sans repas)", "Protocole art. 6 — à vérifier"),
    CASSE_CROUTE("Casse-croûte (service avant 5 h)", "Protocole art. 5"),
    NIGHT("Casse-croûte de nuit (4 h de travail entre 22 h et 7 h)", "Protocole art. 12"),
}

data class ExpenseItem(val kind: ExpenseKind, val count: Int, val amount: Double)

/**
 * Indemnités d'une journée de travail (service). [awayNight] : statut du repos qui suit
 * (null = à préciser ou repos en cours) ; [abroad] : majoration de 18 % appliquée.
 */
data class ShiftExpenses(
    val shift: Shift,
    val date: LocalDate,
    val rates: Rates,
    val meals: Int,
    val awayNight: NightStatus?,
    val abroad: Boolean,
    val items: List<ExpenseItem>,
) {
    val total: Double get() = items.sumOf { it.amount }
    val nightToClarify: Boolean get() = awayNight == null
}

data class ExpenseMonth(val month: YearMonth, val days: List<ShiftExpenses>) {
    val total: Double get() = days.sumOf { it.total }
    fun count(kind: ExpenseKind): Int = days.sumOf { d -> d.items.filter { it.kind == kind }.sumOf { it.count } }
    val meals: Int get() = count(ExpenseKind.MEAL) + count(ExpenseKind.GRAND_DEPLACEMENT_1) + 2 * count(ExpenseKind.GRAND_DEPLACEMENT_2)
    val decouchers: Int get() = count(ExpenseKind.GRAND_DEPLACEMENT_1) + count(ExpenseKind.GRAND_DEPLACEMENT_2) + count(ExpenseKind.DECOUCHER_ONLY)
    val toClarify: Int get() = days.count { it.nightToClarify }
}

object Expenses {
    /** Art. 3 : périodes que l'amplitude du service doit couvrir entièrement. */
    private val MEAL_WINDOWS = listOf(LocalTime.of(11, 45) to LocalTime.of(14, 15), LocalTime.of(18, 45) to LocalTime.of(21, 15))
    private const val ABROAD_MARKUP = 1.18

    /** Taux applicable à une date : le dernier barème entré en vigueur, sinon le plus ancien. */
    fun ratesAt(all: List<Rates>, date: LocalDate): Rates? =
        all.filter { !it.effective.isAfter(date) }.maxByOrNull { it.effective } ?: all.minByOrNull { it.effective }

    /** Nombre de périodes de repas (art. 3) entièrement couvertes par l'amplitude du service. */
    fun mealsCovered(shift: Shift, zone: ZoneId): Int {
        var n = 0
        var day = shift.start.atZone(zone).toLocalDate()
        val last = shift.end.atZone(zone).toLocalDate()
        while (!day.isAfter(last)) {
            for ((a, b) in MEAL_WINDOWS) {
                val s = day.atTime(a).atZone(zone).toInstant()
                val e = day.atTime(b).atZone(zone).toInstant()
                if (!shift.start.isAfter(s) && !shift.end.isBefore(e)) n++
            }
            day = day.plusDays(1)
        }
        return n
    }

    fun compute(
        analysis: Analysis,
        nights: List<Night>,
        rates: List<Rates>,
        zone: ZoneId,
        abroadOf: (Shift) -> Boolean = { false },
    ): List<ShiftExpenses> = analysis.shifts.mapNotNull { s ->
        val date = s.start.atZone(zone).toLocalDate()
        val r = ratesAt(rates, date) ?: return@mapNotNull null
        val night = nights.firstOrNull { it.rest == s.restAfter }
        val status = night?.status
        val abroad = abroadOf(s)
        val k = if (abroad) ABROAD_MARKUP else 1.0
        val meals = mealsCovered(s, zone)
        val items = mutableListOf<ExpenseItem>()
        fun add(kind: ExpenseKind, count: Int, unit: Double) {
            if (count > 0) items += ExpenseItem(kind, count, round2(count * unit * k))
        }
        if (status == NightStatus.AWAY) {
            // Art. 6 : grand déplacement ; au-delà de 2 repas, les suivants en indemnité de repas.
            when {
                meals >= 2 -> add(ExpenseKind.GRAND_DEPLACEMENT_2, 1, r.grandDeplacement2Repas)
                meals == 1 -> add(ExpenseKind.GRAND_DEPLACEMENT_1, 1, r.grandDeplacement1Repas)
                else -> add(ExpenseKind.DECOUCHER_ONLY, 1, r.decoucher)
            }
            add(ExpenseKind.MEAL, (meals - 2).coerceAtLeast(0), r.repas)
            // Art. 5 : casse-croûte non cumulable avec l'indemnité de découcher.
        } else {
            add(ExpenseKind.MEAL, meals, r.repas)
            if (s.start.atZone(zone).toLocalTime() < LocalTime.of(5, 0)) add(ExpenseKind.CASSE_CROUTE, 1, r.casseCroute)
        }
        // Art. 12 : seulement si aucune autre indemnité n'est perçue pour ce service.
        if (items.isEmpty()) {
            val nightWork = Timeline.minutesInHours(s.periods, zone, 22, 7) {
                it.type == ActivityType.DRIVING || it.type == ActivityType.WORK
            }
            if (nightWork >= 4 * 60) add(ExpenseKind.NIGHT, 1, r.repasUniqueNuit)
        }
        ShiftExpenses(s, date, r, meals, status, abroad, items)
    }

    fun months(list: List<ShiftExpenses>): List<ExpenseMonth> = list
        .groupBy { YearMonth.from(it.date) }
        .map { (m, l) -> ExpenseMonth(m, l) }
        .sortedByDescending { it.month }

    private fun round2(x: Double): Double = (x * 100).roundToLong() / 100.0
}
