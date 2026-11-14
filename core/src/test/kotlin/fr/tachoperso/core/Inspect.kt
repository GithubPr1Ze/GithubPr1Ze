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
    println("Traversées ferry/train : ${h.crossings.size}, total ${hm(h.crossings.sumOf { it.minutes })}, sans durée ${h.crossings.count { !it.measured }}")
    h.crossingMonths.forEach { println("  ${it.month} : ${it.count} traversées, ${hm(it.minutes)}, sans durée ${it.unmeasured}") }
}
