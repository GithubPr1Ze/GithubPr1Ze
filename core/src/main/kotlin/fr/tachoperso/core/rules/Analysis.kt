package fr.tachoperso.core.rules

import fr.tachoperso.core.tacho.ActivityType
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

enum class Severity(val label: String) {
    OK("Conforme"),
    WARNING("À vérifier"),
    INFRACTION("Infraction"),
}

/**
 * Règles contrôlées. Sources : règlement (CE) n° 561/2006 consolidé au 20/08/2020 (modifié par le
 * règlement (UE) 2020/1054) et Code des transports (conducteurs « grands routiers »).
 */
enum class Rule(val title: String, val article: String) {
    BREAK("Pause de conduite", "Règl. (CE) 561/2006, art. 7"),
    DAILY_DRIVING("Durée de conduite journalière", "Règl. (CE) 561/2006, art. 6 § 1"),
    WEEKLY_DRIVING("Durée de conduite hebdomadaire", "Règl. (CE) 561/2006, art. 6 § 2"),
    FORTNIGHT_DRIVING("Conduite sur deux semaines consécutives", "Règl. (CE) 561/2006, art. 6 § 3"),
    DAILY_REST("Repos journalier dans les 24 h", "Règl. (CE) 561/2006, art. 8 § 2"),
    REDUCED_DAILY_RESTS("Repos journaliers réduits", "Règl. (CE) 561/2006, art. 8 § 4"),
    WEEKLY_REST_DELAY("Délai du repos hebdomadaire", "Règl. (CE) 561/2006, art. 8 § 6"),
    WEEKLY_REST("Repos hebdomadaires réduits consécutifs", "Règl. (CE) 561/2006, art. 8 § 6"),
    WEEKLY_REST_COMPENSATION("Compensation du repos hebdomadaire réduit", "Règl. (CE) 561/2006, art. 8 § 6 ter et § 7"),
    CREW("Conduite en équipage", "Règl. (CE) 561/2006, art. 8 § 5"),
    SERVICE_DAILY("Temps de service journalier", "Code des transports, art. D3312-51"),
    SERVICE_WEEKLY("Temps de service hebdomadaire", "Code des transports, art. D3312-50"),
    SERVICE_QUARTER("Temps de service trimestriel", "Code des transports, art. D3312-50"),
    NIGHT_WORK("Travail de nuit", "Code des transports, art. L3312-1"),
    WORK_BREAK("Pause du temps de travail", "Code des transports, art. L3312-2"),
}

data class Finding(
    val rule: Rule,
    val severity: Severity,
    val at: Instant,
    val observed: String,
    val limit: String,
    val detail: String = "",
)

/** Bloc de repos continu (périodes « repos » fusionnées, carte insérée ou non). */
data class RestBlock(val start: Instant, val end: Instant, val truncated: Boolean) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
    val isWeekly: Boolean get() = minutes >= Limits.WEEKLY_REST_MIN
    val isRegularWeekly: Boolean get() = minutes >= Limits.WEEKLY_REST_REGULAR
}

/** Période de travail entre deux repos d'au moins 9 h (journée au sens de la RSE). */
data class Shift(
    val start: Instant,
    val end: Instant,
    val periods: List<Period>,
    val restBefore: RestBlock?,
    val restAfter: RestBlock?,
) {
    val driving: Long get() = Timeline.minutes(periods, ActivityType.DRIVING)
    val work: Long get() = Timeline.minutes(periods, ActivityType.WORK)
    val availability: Long get() = Timeline.minutes(periods, ActivityType.AVAILABILITY)
    val breaks: Long get() = Timeline.minutes(periods, ActivityType.REST)
    val service: Long get() = driving + work + availability
    val amplitude: Long get() = Duration.between(start, end).toMinutes()
    val crew: Boolean get() = periods.any { it.crew && it.type != ActivityType.REST }
}

/** Seuils, en minutes. */
object Limits {
    const val DRIVING_BEFORE_BREAK = 270
    const val BREAK_FULL = 45
    const val BREAK_FIRST = 15
    const val BREAK_SECOND = 30
    const val DAILY_DRIVING = 540
    const val DAILY_DRIVING_EXTENDED = 600
    const val EXTENSIONS_PER_WEEK = 2
    const val WEEKLY_DRIVING = 56 * 60
    const val FORTNIGHT_DRIVING = 90 * 60
    const val DAILY_REST_REGULAR = 660
    const val DAILY_REST_REDUCED = 540
    const val SPLIT_FIRST_PART = 180
    const val REDUCED_DAILY_MAX = 3
    const val WEEKLY_REST_MIN = 24 * 60
    const val WEEKLY_REST_REGULAR = 45 * 60
    const val WEEKLY_REST_MAX_DELAY = 6 * 24 * 60
    const val SERVICE_DAILY = 12 * 60
    const val SERVICE_WEEKLY = 56 * 60
    const val SERVICE_QUARTER = 689 * 60
    const val NIGHT_WORK_DAILY = 10 * 60
    const val WORK_BREAK_6H = 30
    const val WORK_BREAK_9H = 45
    const val WORK_BREAK_MIN_PART = 15
    const val WORK_BEFORE_BREAK = 6 * 60
    const val WORK_9H = 9 * 60
    const val EQUIVALENCE_WEEKLY = 43 * 60
    const val LEGAL_WEEKLY = 35 * 60
}

fun hm(minutes: Long): String =
    (if (minutes < 0) "-" else "") + "%dh%02d".format(Math.abs(minutes) / 60, Math.abs(minutes) % 60)

/**
 * Analyse réglementaire d'une chronologie d'activités. Journées, semaines (lundi 00h00 – dimanche
 * 24h00, art. 4 i) et trimestres sont pris dans le fuseau [zone].
 */
class Analysis(val timeline: List<Period>, val zone: ZoneId = ZoneId.of("Europe/Paris")) {
    val dataStart: Instant? = timeline.firstOrNull()?.start
    val dataEnd: Instant? = timeline.lastOrNull()?.end

    val restBlocks: List<RestBlock> = buildRestBlocks()
    val shifts: List<Shift> = buildShifts()
    val weeklyRests: List<RestBlock> = restBlocks.filter { it.isWeekly }

    val findings: List<Finding> by lazy {
        val out = mutableListOf<Finding>()
        checkShifts(out)
        checkWeeks(out)
        checkWeeklyRests(out)
        checkQuarters(out)
        out.sortedByDescending { it.at }
    }

    fun localDate(i: Instant): LocalDate = i.atZone(zone).toLocalDate()
    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun startOf(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()

    fun weekSlice(monday: LocalDate): List<Period> =
        Timeline.slice(timeline, startOf(monday), startOf(monday.plusWeeks(1)))

    fun weekDriving(monday: LocalDate): Long = Timeline.minutes(weekSlice(monday), ActivityType.DRIVING)
    fun weekService(monday: LocalDate): Long = weekSlice(monday).filter { !it.isRest }.sumOf { it.minutes }
    fun fortnightDriving(monday: LocalDate): Long = weekDriving(monday) + weekDriving(monday.minusWeeks(1))
    fun extensionsUsed(monday: LocalDate): Int =
        shifts.count { monday(localDate(it.start)) == monday && it.driving > Limits.DAILY_DRIVING }

    fun quarterStart(d: LocalDate): LocalDate = d.with(IsoFields.DAY_OF_QUARTER, 1)
    fun quarterService(d: LocalDate): Long {
        val q = quarterStart(d)
        return Timeline.slice(timeline, startOf(q), startOf(q.plusMonths(3))).filter { !it.isRest }.sumOf { it.minutes }
    }

    /** Minutes de nuit au sens de l'accord de branche du 14/11/2001 (21 h – 6 h). */
    fun nightMinutes(periods: List<Period>): Long =
        Timeline.minutesInHours(periods, zone, 21, 6) { !it.isRest }

    fun findingsOn(date: LocalDate): List<Finding> = findings.filter { localDate(it.at) == date }

    fun worstOn(date: LocalDate): Severity = findingsOn(date).maxOfOrNull { it.severity } ?: Severity.OK

    fun shiftsStartingOn(date: LocalDate): List<Shift> = shifts.filter { localDate(it.start) == date }

    // --- Construction ---

    private fun buildRestBlocks(): List<RestBlock> {
        val out = mutableListOf<RestBlock>()
        var start: Instant? = null
        var end: Instant? = null
        for (p in timeline) {
            if (p.isRest) {
                if (start == null) start = p.start
                end = p.end
            } else if (start != null) {
                out += RestBlock(start, end!!, truncated = start == dataStart)
                start = null
            }
        }
        if (start != null) out += RestBlock(start, end!!, truncated = true)
        return out
    }

    private fun buildShifts(): List<Shift> {
        if (timeline.isEmpty()) return emptyList()
        val separators: List<RestBlock?> = restBlocks.filter { it.minutes >= Limits.DAILY_REST_REDUCED } + listOf(null)
        val out = mutableListOf<Shift>()
        var previous: RestBlock? = null
        var cursor = dataStart!!
        for (sep in separators) {
            val periods = Timeline.slice(timeline, cursor, sep?.start ?: dataEnd!!)
            val active = periods.filter { !it.isRest }
            if (active.isNotEmpty()) {
                val s = active.first().start
                val e = active.last().end
                out += Shift(s, e, Timeline.slice(periods, s, e), previous, sep)
            }
            if (sep != null) {
                previous = sep
                cursor = sep.end
            }
        }
        return out
    }

    // --- Contrôles par journée de travail ---

    private fun checkShifts(out: MutableList<Finding>) {
        var reducedSinceWeekly = 0
        val extensions = mutableMapOf<LocalDate, Int>()
        for (shift in shifts) {
            if (shift.restBefore?.isWeekly == true) reducedSinceWeekly = 0
            checkBreaks(shift, out)

            if (shift.driving > Limits.DAILY_DRIVING_EXTENDED) {
                out += Finding(
                    Rule.DAILY_DRIVING, Severity.INFRACTION, shift.start, hm(shift.driving), "10h00",
                    "Dépassement de ${hm(shift.driving - Limits.DAILY_DRIVING_EXTENDED)} sur la durée maximale.",
                )
            } else if (shift.driving > Limits.DAILY_DRIVING) {
                val week = monday(localDate(shift.start))
                val n = (extensions[week] ?: 0) + 1
                extensions[week] = n
                if (n > Limits.EXTENSIONS_PER_WEEK) {
                    out += Finding(
                        Rule.DAILY_DRIVING, Severity.INFRACTION, shift.start, hm(shift.driving), "9h00",
                        "${n}e journée de plus de 9 h dans la semaine (2 autorisées).",
                    )
                }
            }

            val rest = dailyRestWithin24h(shift)
            if (rest != null) {
                val (minutes, split) = rest
                when {
                    minutes >= Limits.DAILY_REST_REGULAR -> Unit
                    minutes >= Limits.DAILY_REST_REDUCED && split -> Unit
                    minutes >= Limits.DAILY_REST_REDUCED -> if (shift.restAfter?.isWeekly != true) {
                        reducedSinceWeekly++
                        if (reducedSinceWeekly > Limits.REDUCED_DAILY_MAX) {
                            out += Finding(
                                Rule.REDUCED_DAILY_RESTS, Severity.INFRACTION, shift.start,
                                "$reducedSinceWeekly repos réduits", "3 maximum", "Entre deux repos hebdomadaires.",
                            )
                        }
                    }
                    else -> out += Finding(
                        Rule.DAILY_REST, Severity.INFRACTION, shift.start, hm(minutes), "9h00",
                        "Repos pris dans les 24 h suivant la fin du repos précédent : il manque " +
                            "${hm(Limits.DAILY_REST_REDUCED - minutes)}.",
                    )
                }
            }

            if (shift.crew) {
                out += Finding(
                    Rule.CREW, Severity.WARNING, shift.start, "Équipage", "—",
                    "Les règles propres à la conduite en équipage (repos de 9 h dans les 30 h) ne sont pas contrôlées.",
                )
            }

            if (shift.service > Limits.SERVICE_DAILY) {
                out += Finding(
                    Rule.SERVICE_DAILY, Severity.INFRACTION, shift.start, hm(shift.service), "12h00",
                    "Temps de service = conduite + autres tâches + disponibilité.",
                )
            }
            val travail = shift.driving + shift.work
            val nightWork = Timeline.minutesInHours(shift.periods, zone, 0, 5) { it.isWork }
            if (nightWork > 0 && travail > Limits.NIGHT_WORK_DAILY) {
                out += Finding(
                    Rule.NIGHT_WORK, Severity.INFRACTION, shift.start, hm(travail), "10h00",
                    "Travail entre 0 h et 5 h (${hm(nightWork)}) : le travail de la journée ne peut excéder 10 h. " +
                        "Vérifiez si un accord collectif applicable prévoit une dérogation.",
                )
            }
            checkWorkBreaks(shift, travail, out)
        }
    }

    /** Art. 7 : 45 min après 4h30 de conduite, ou 15 min puis 30 min. */
    private fun checkBreaks(shift: Shift, out: MutableList<Finding>) {
        var driving = 0L
        var firstPart = false
        var segmentStart: Instant? = null
        fun close() {
            if (driving > Limits.DRIVING_BEFORE_BREAK) {
                out += Finding(
                    Rule.BREAK, Severity.INFRACTION, segmentStart ?: shift.start, hm(driving), "4h30",
                    "Conduite cumulée sans pause conforme (45 min, ou 15 min puis 30 min).",
                )
            }
            driving = 0
            firstPart = false
            segmentStart = null
        }
        for (p in shift.periods) {
            if (p.type == ActivityType.DRIVING) {
                if (segmentStart == null) segmentStart = p.start
                driving += p.minutes
            } else if (p.isRest) {
                when {
                    p.minutes >= Limits.BREAK_FULL -> close()
                    p.minutes >= Limits.BREAK_SECOND && firstPart -> close()
                    p.minutes >= Limits.BREAK_FIRST -> firstPart = true
                }
            }
        }
        close()
    }

    /**
     * Art. 8 § 2 : repos journalier pris dans les 24 h suivant la fin du repos précédent.
     * Renvoie (minutes de repos dans la fenêtre, première partie d'au moins 3 h prise avant) ;
     * null si la fenêtre n'est pas écoulée ou si le début de la journée est inconnu.
     */
    private fun dailyRestWithin24h(shift: Shift): Pair<Long, Boolean>? {
        val before = shift.restBefore ?: return null
        val windowEnd = before.end.plusSeconds(24 * 3600)
        val after = shift.restAfter
        val portion = when {
            after == null -> 0L
            after.start >= windowEnd -> 0L
            else -> Duration.between(after.start, minOf(after.end, windowEnd)).toMinutes()
        }
        if (portion < Limits.DAILY_REST_REDUCED && dataEnd!! < windowEnd) return null
        val split = shift.periods.any { it.isRest && it.minutes >= Limits.SPLIT_FIRST_PART && it.end <= windowEnd }
        return portion to split
    }

    /** L3312-2 : 30 min de pause au-delà de 6 h de travail, 45 min au-delà de 9 h, par fractions de 15 min. */
    private fun checkWorkBreaks(shift: Shift, travail: Long, out: MutableList<Finding>) {
        val pauses = shift.periods.filter { it.isRest && it.minutes >= Limits.WORK_BREAK_MIN_PART }.sumOf { it.minutes }
        val required = when {
            travail > Limits.WORK_9H -> Limits.WORK_BREAK_9H
            travail > Limits.WORK_BEFORE_BREAK -> Limits.WORK_BREAK_6H
            else -> 0
        }
        if (pauses < required) {
            out += Finding(
                Rule.WORK_BREAK, Severity.INFRACTION, shift.start, hm(pauses), hm(required.toLong()),
                "Pauses d'au moins 15 min pour ${hm(travail)} de travail (conduite + autres tâches).",
            )
        }
        var stretch = 0L
        var stretchStart: Instant? = null
        var reported = false
        for (p in shift.periods) {
            if (p.isRest && p.minutes >= Limits.WORK_BREAK_MIN_PART) {
                stretch = 0
                stretchStart = null
                reported = false
            } else if (p.isWork) {
                if (stretchStart == null) stretchStart = p.start
                stretch += p.minutes
                if (stretch > Limits.WORK_BEFORE_BREAK && !reported) {
                    reported = true
                    out += Finding(
                        Rule.WORK_BREAK, Severity.INFRACTION, stretchStart, "plus de 6h00", "6h00",
                        "Travail de plus de 6 heures consécutives sans pause.",
                    )
                }
            }
        }
    }

    // --- Contrôles par semaine et par trimestre ---

    private fun checkWeeks(out: MutableList<Finding>) {
        val start = dataStart ?: return
        var week = monday(localDate(start))
        val last = monday(localDate(dataEnd!!))
        while (!week.isAfter(last)) {
            val at = startOf(week)
            val driving = weekDriving(week)
            if (driving > Limits.WEEKLY_DRIVING) {
                out += Finding(Rule.WEEKLY_DRIVING, Severity.INFRACTION, at, hm(driving), "56h00", "Semaine du lundi au dimanche.")
            }
            val fortnight = fortnightDriving(week)
            if (fortnight > Limits.FORTNIGHT_DRIVING) {
                out += Finding(Rule.FORTNIGHT_DRIVING, Severity.INFRACTION, at, hm(fortnight), "90h00", "Cette semaine et la précédente.")
            }
            val service = weekService(week)
            if (service > Limits.SERVICE_WEEKLY) {
                out += Finding(
                    Rule.SERVICE_WEEKLY, Severity.INFRACTION, at, hm(service), "56h00",
                    "Durée maximale sur une semaine isolée (conducteurs grands routiers).",
                )
            }
            week = week.plusWeeks(1)
        }
    }

    private fun checkQuarters(out: MutableList<Finding>) {
        val start = dataStart ?: return
        var q = quarterStart(localDate(start))
        while (!q.isAfter(localDate(dataEnd!!))) {
            val service = quarterService(q)
            if (service > Limits.SERVICE_QUARTER) {
                out += Finding(
                    Rule.SERVICE_QUARTER, Severity.INFRACTION, startOf(q), hm(service), "689h00",
                    "Trimestre civil (moyenne de 53 h par semaine).",
                )
            }
            q = q.plusMonths(3)
        }
    }

    // --- Repos hebdomadaires ---

    private fun checkWeeklyRests(out: MutableList<Finding>) {
        for ((a, b) in weeklyRests.zipWithNext()) {
            val gap = Duration.between(a.end, b.start).toMinutes()
            if (gap > Limits.WEEKLY_REST_MAX_DELAY) {
                out += Finding(
                    Rule.WEEKLY_REST_DELAY, Severity.INFRACTION, a.end, hm(gap), "144h00",
                    "Le repos hebdomadaire doit commencer au plus tard à la fin de six périodes de 24 h.",
                )
            }
        }
        weeklyRests.lastOrNull()?.let { last ->
            if (last.end != dataEnd) {
                val gap = Duration.between(last.end, dataEnd).toMinutes()
                if (gap > Limits.WEEKLY_REST_MAX_DELAY) {
                    out += Finding(
                        Rule.WEEKLY_REST_DELAY, Severity.INFRACTION, last.end, hm(gap), "144h00",
                        "Aucun repos hebdomadaire depuis plus de six périodes de 24 h.",
                    )
                }
            }
        }

        val complete = weeklyRests.filter { !it.truncated }
        var run = 0
        for (r in complete) {
            run = if (r.isRegularWeekly) 0 else run + 1
            if (run == 2) {
                out += Finding(
                    Rule.WEEKLY_REST, Severity.WARNING, r.start, "2 repos réduits consécutifs", "1 normal sur 2 semaines",
                    "Autorisé seulement en transport international, hors de l'État d'établissement et de résidence " +
                        "(art. 8 § 6 al. 3), avec au moins 4 repos dont 2 normaux sur 4 semaines.",
                )
            } else if (run > 2) {
                out += Finding(
                    Rule.WEEKLY_REST, Severity.INFRACTION, r.start, "$run repos réduits consécutifs", "2 maximum",
                    "Au moins un repos hebdomadaire normal de 45 h est requis.",
                )
            }
        }

        val used = mutableSetOf<RestBlock>()
        for (r in complete.filter { !it.isRegularWeekly }) {
            val deficit = Limits.WEEKLY_REST_REGULAR - r.minutes
            val deadline = startOf(monday(localDate(r.start)).plusWeeks(4))
            val due = localDate(deadline.minusSeconds(1))
            val comp = restBlocks.firstOrNull { b ->
                val base = if (b.isWeekly) Limits.WEEKLY_REST_REGULAR else Limits.DAILY_REST_REDUCED
                b !in used && b.start >= r.end && b.end <= deadline && b.minutes >= Limits.DAILY_REST_REDUCED &&
                    b.minutes - base >= deficit
            }
            when {
                comp != null -> used += comp
                dataEnd!! >= deadline -> out += Finding(
                    Rule.WEEKLY_REST_COMPENSATION, Severity.INFRACTION, r.start, "Repos de ${hm(r.minutes)}",
                    "Compensation de ${hm(deficit)}", "Compensation non trouvée avant le $due.",
                )
                else -> out += Finding(
                    Rule.WEEKLY_REST_COMPENSATION, Severity.WARNING, r.start, "Repos de ${hm(r.minutes)}",
                    "Compensation de ${hm(deficit)}",
                    "À prendre en bloc, rattachée à un repos d'au moins 9 h, avant le $due.",
                )
            }
        }
    }
}
