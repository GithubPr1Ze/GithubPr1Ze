package fr.tachoperso.core.rules

import java.time.LocalDate
import java.time.YearMonth

/**
 * Amplitude d'une journée de travail (prise → fin de poste, entre deux repos journaliers), diminuée
 * de la coupure obligatoire de 45 min lorsque la conduite dépasse 4 h 30 ou l'amplitude 6 h.
 * La journée est rattachée à la date (et au mois) de prise de poste, heure de Paris.
 */
data class AmplitudeDay(val date: LocalDate, val shift: Shift) {
    val amplitude: Long get() = shift.amplitude
    val driving: Long get() = shift.driving
    val deduction: Long get() = Amplitude.deduction(shift)
    val net: Long get() = (amplitude - deduction).coerceAtLeast(0)
    /** Journée encore en cours (pas de repos journalier après la dernière activité lue). */
    val inProgress: Boolean get() = shift.restAfter == null
}

/** Récapitulatif mensuel des amplitudes. */
data class AmplitudeMonth(val month: YearMonth, val days: List<AmplitudeDay>) {
    val count: Int get() = days.size
    val gross: Long get() = days.sumOf { it.amplitude }
    val deductions: Long get() = days.sumOf { it.deduction }
    val net: Long get() = days.sumOf { it.net }
    val average: Long get() = if (days.isEmpty()) 0 else net / days.size
    val longest: AmplitudeDay? get() = days.maxByOrNull { it.amplitude }
}

object Amplitude {
    const val BREAK = 45L
    const val DRIVING_THRESHOLD = 270L
    const val AMPLITUDE_THRESHOLD = 360L

    fun deduction(s: Shift): Long =
        if (s.driving > DRIVING_THRESHOLD || s.amplitude > AMPLITUDE_THRESHOLD) BREAK else 0

    /** Toutes les journées de travail, plus récentes d'abord, groupées par mois de prise de poste. */
    fun months(a: Analysis): List<AmplitudeMonth> =
        a.shifts.filter { it.service > 0 }
            .map { AmplitudeDay(a.localDate(it.start), it) }
            .groupBy { YearMonth.from(it.date) }
            .map { (m, d) -> AmplitudeMonth(m, d.sortedBy { it.shift.start }) }
            .sortedByDescending { it.month }
}
