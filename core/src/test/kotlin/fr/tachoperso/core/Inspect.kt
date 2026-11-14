package fr.tachoperso.core

import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.hm
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.DddFormat
import java.io.File

/** Outil de mise au point : analyse réglementaire d'un .DDD, sans afficher l'identité. */
fun main(args: Array<String>) {
    val report = CardReport(DddFormat.read(File(args[0]).readBytes()))
    val h = DriverHistory(listOf(report))
    val a = h.analysis
    println("Jours ${h.days.size}, journées de travail ${a.shifts.size}, repos hebdo ${a.weeklyRests.size}")
    println("Constats par règle :")
    a.findings.groupBy { it.rule to it.severity }.forEach { (k, v) -> println("  ${k.second} ${k.first.title} : ${v.size}") }
    println("Détail des 25 plus récents :")
    a.findings.take(25).forEach { println("  ${a.localDate(it.at)} ${it.severity} ${it.rule.title} constaté ${it.observed} limite ${it.limit} — ${it.detail}") }
    println("Dernières journées :")
    a.shifts.takeLast(12).forEach {
        println("  ${it.start.atZone(a.zone).toLocalDateTime()} → ${it.end.atZone(a.zone).toLocalTime()}  cond ${hm(it.driving)} trav ${hm(it.work)} dispo ${hm(it.availability)} service ${hm(it.service)} ampl ${hm(it.amplitude)} nuit ${hm(a.nightMinutes(it.periods))} reposApres ${it.restAfter?.minutes?.let(::hm)}")
    }
    println("Lieux : ${h.places.size} (avec GNSS ${h.places.count { it.gnss?.point != null }}), positions 3 h : ${h.gnssDriving.size} (avec GNSS ${h.gnssDriving.count { it.gnss?.point != null }})")
    h.places.firstOrNull()?.let { println("  du ${it.time} au ${h.places.last().time}") }
    h.gnssDriving.firstOrNull()?.let { println("  GNSS du ${it.time} au ${h.gnssDriving.last().time}") }
    println("  Pays : " + h.places.groupingBy { it.country }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
    println("  Types : " + h.places.groupingBy { it.type?.name ?: "?" }.eachCount())
    println("  Précisions (m) : " + h.locations.mapNotNull { it.accuracyM }.groupingBy { it }.eachCount().toSortedMap())
    println("Dernières localisations :")
    h.locations.takeLast(10).forEach { println("  ${it.time.atZone(a.zone).toLocalDateTime()} ${it.label} ${it.country ?: ""} ${it.point?.label() ?: "sans position"} ${it.odometerKm} km") }
    println("Traversées ferry/train : ${h.ferry.size}, total ${hm(h.ferry.sumOf { it.minutes })}, en repos ${hm(h.ferry.sumOf { it.restMinutes })}, estimées ${h.ferry.count { it.estimated }}, inconnues ${h.ferry.count { !it.known }}")
    h.ferryMonths.forEach { m -> println("  ${m.month} : ${m.count} trav., ${hm(m.minutes)} dont repos ${hm(m.restMinutes)}, estimées ${m.estimatedCount} (${hm(m.estimatedMinutes)}), inconnues ${m.unknownCount}") }
    h.ferry.filter { it.estimated || !it.known }.forEach { println("    ${it.from.atZone(a.zone).toLocalDateTime()} → ${it.to?.atZone(a.zone)?.toLocalTime()} ${hm(it.minutes)} coupé après ${it.cutAfterSeconds} s") }
    println("Zones de repos hebdo : " + h.restClusters.take(5).joinToString { "${it.center.label()} ×${it.weeklyRests}" })
    println("Domicile : ${h.home?.label()}")
    println("Nuits : ${h.nights.size}, découchés ${h.nights.count { it.status == fr.tachoperso.core.rules.NightStatus.AWAY }}, domicile ${h.nights.count { it.status == fr.tachoperso.core.rules.NightStatus.HOME }}, inconnus ${h.nights.count { it.status == null }}, sur ferry ${h.nights.count { it.onFerry }}")
    println("  marges : " + h.nights.groupingBy { u -> when (val x = u.uncertaintyKm) { null -> "aucune"; 0.0 -> "0"; in 0.0..2.0 -> "<=2km"; in 2.0..30.0 -> "<=30km"; else -> ">30km" } }.eachCount())
    h.nightMonths.forEach { println("  ${it.month} : découchés ${it.away}, domicile ${it.home}, inconnus ${it.unknown}") }
    println("Dernières nuits :")
    h.nights.takeLast(14).forEach { println("  ${it.rest.start.atZone(a.zone).toLocalDateTime()} → ${it.rest.end.atZone(a.zone).toLocalDateTime()} ${hm(it.rest.minutes)} ${if (it.weekly) "HEBDO" else ""} ${it.status} ${it.point?.label()} (±${it.uncertaintyKm?.toInt()} km) ${if (it.onFerry) "FERRY" else ""}") }
    val tl = h.timeline
    val outAtStart = h.nights.count { n -> tl.any { it.cardOut && it.start <= n.rest.start.plusSeconds(900) && it.end > n.rest.start } }
    val outAtEnd = h.nights.count { n -> tl.any { it.cardOut && it.end >= n.rest.end.minusSeconds(900) && it.start < n.rest.end } }
    println("Nuits avec carte retirée au début : $outAtStart, à la fin : $outAtEnd, sur ${h.nights.size}")
    val rates = listOf(fr.tachoperso.core.rules.Rates(java.time.LocalDate.of(2026, 1, 1), "Avenant 81", 16.36, 10.07, 9.81, 4.42, 8.87, 52.31, 68.67))
    for (abroad in listOf(false, true)) {
        val ex = h.expenses(rates, abroad)
        println("Frais (majoration étranger $abroad) : total %.2f €".format(ex.sumOf { it.total }))
        fr.tachoperso.core.rules.Expenses.months(ex).take(4).forEach { m ->
            println("  ${m.month} : %.2f €, repas ${m.meals}, découchers ${m.decouchers}, casse-croûte ${m.count(fr.tachoperso.core.rules.ExpenseKind.CASSE_CROUTE)}, nuit ${m.count(fr.tachoperso.core.rules.ExpenseKind.NIGHT)}, à préciser ${m.toClarify}".format(m.total))
        }
    }
    val r = fr.tachoperso.core.rules.MonthlyReport.build(h, h.expenses(rates, false), java.time.YearMonth.of(2026, 10))
    println("Récap ${r.month} : jours travaillés ${r.workedDays}, conduite ${hm(r.driving)}, service ${hm(r.service)}, nuit ${hm(r.nightMinutes)}, km ${r.km}, frais %.2f, découchés ${r.decouchers}, ferry ${r.ferry?.let { hm(it.minutes) }}, constats ${r.findings.size}".format(r.expenses?.total ?: 0.0))
    r.weeks.forEach { println("  semaine ${it.monday} conduite ${hm(it.driving)} service ${hm(it.service)} >35 ${hm(it.over35)} >43 ${hm(it.over43)}") }
}
