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
}
