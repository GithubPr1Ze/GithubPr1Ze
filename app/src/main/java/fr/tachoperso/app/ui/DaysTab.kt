package fr.tachoperso.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.Severity
import fr.tachoperso.core.rules.hm
import fr.tachoperso.core.tacho.ActivityType
import java.time.LocalDate

@Composable
fun DaysTab(history: DriverHistory, onOpenDay: (LocalDate) -> Unit) {
    val a = history.analysis
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Legend() }
        items(history.localDays, key = { it.date.toString() }) { day ->
            val severity = a.worstOn(day.date)
            Panel(modifier = Modifier.clickable { onOpenDay(day.date) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(day.date.long(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (severity != Severity.OK) {
                        SeverityBadge(severity)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("${day.distanceKm} km", style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DayBar(day.date, day.periods)
                if (day.isWorked) {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Conduite", hm(day.minutes(ActivityType.DRIVING)), Modifier.weight(1f))
                        Metric("Travail", hm(day.minutes(ActivityType.WORK)), Modifier.weight(1f))
                        Metric("Dispo.", hm(day.minutes(ActivityType.AVAILABILITY)), Modifier.weight(1f))
                        Metric("Service", hm(day.serviceMinutes), Modifier.weight(1f))
                    }
                } else {
                    Text(
                        if (day.minutesCardOut > 0) "Repos (carte retirée)" else "Repos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun DayDetail(history: DriverHistory, date: LocalDate) {
    val a = history.analysis
    val day = history.localDays.firstOrNull { it.date == date }
    val findings = a.findingsOn(date)
    val shifts = a.shiftsStartingOn(date)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(date.long(), style = MaterialTheme.typography.titleLarge)
        }
        if (day != null) {
            item {
                Panel(title = "Activités (heure de Paris)") {
                    DayBar(date, day.periods, height = 22)
                    Legend()
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Conduite", hm(day.minutes(ActivityType.DRIVING)), Modifier.weight(1f))
                        Metric("Travail", hm(day.minutes(ActivityType.WORK)), Modifier.weight(1f))
                        Metric("Dispo.", hm(day.minutes(ActivityType.AVAILABILITY)), Modifier.weight(1f))
                        Metric("Repos", hm(day.restMinutes), Modifier.weight(1f))
                    }
                    KeyValue("Distance", "${day.distanceKm} km")
                }
            }
        }
        if (findings.isNotEmpty()) {
            item {
                Panel(title = "Contrôles réglementaires") {
                    Column {
                        findings.forEachIndexed { i, f ->
                            if (i > 0) Divider()
                            FindingRow(f, showDate = false)
                        }
                    }
                }
            }
        } else if (shifts.isNotEmpty()) {
            item {
                Panel(title = "Contrôles réglementaires") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SeverityBadge(Severity.OK)
                        Spacer(Modifier.width(8.dp))
                        Text("Aucune infraction relevée pour cette journée.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        shifts.forEach { s ->
            item {
                Panel(title = "Journée de travail ${s.start.localTime()} – ${s.end.localTime()}") {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Conduite", hm(s.driving), Modifier.weight(1f))
                        Metric("Service", hm(s.service), Modifier.weight(1f))
                        Metric("Amplitude", hm(s.amplitude), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Nuit (21h–6h)", hm(a.nightMinutes(s.periods)), Modifier.weight(1f))
                        Metric("Pauses", hm(s.breaks), Modifier.weight(1f))
                        Metric("Repos suivant", s.restAfter?.let { hm(it.minutes) } ?: "en cours", Modifier.weight(1f))
                    }
                    if (s.end.atZone(ZONE).toLocalDate() != date) {
                        Text("Se termine le ${s.end.local()}.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (day != null) {
            item {
                Panel(title = "Détail des périodes") {
                    Column {
                        day.periods.forEachIndexed { i, p ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.width(4.dp).height(18.dp).background(
                                        if (p.cardOut && p.type == ActivityType.REST) CardOutColor else activityColor(p.type),
                                    ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${p.start.localTime()} – ${p.end.localTime()}",
                                    style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                    modifier = Modifier.width(118.dp),
                                )
                                Text(
                                    activityLabel(p.type) + if (p.cardOut) " · carte retirée" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(hm(p.minutes), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
