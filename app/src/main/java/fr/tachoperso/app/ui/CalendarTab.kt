package fr.tachoperso.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.Severity
import fr.tachoperso.core.rules.hm
import fr.tachoperso.core.tacho.ActivityType
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarTab(history: DriverHistory, onOpenDay: (LocalDate) -> Unit) {
    val a = history.analysis
    val byDate = history.localDays.associateBy { it.date }
    val lastDate = history.localDays.firstOrNull()?.date ?: LocalDate.now(ZONE)
    val firstDate = history.localDays.lastOrNull()?.date ?: lastDate
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(lastDate).toString()) }
    val month = YearMonth.parse(monthText)
    val today = LocalDate.now(ZONE)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { monthText = month.minusMonths(1).toString() }, enabled = month > YearMonth.from(firstDate)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
                    }
                    Text(
                        monthFmt.format(month.atDay(1)).capitalized(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { monthText = month.plusMonths(1).toString() }, enabled = month < YearMonth.from(lastDate)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("L", "M", "M", "J", "V", "S", "D").forEach {
                        Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val first = month.atDay(1)
                val offset = first.dayOfWeek.value - 1
                val cells = offset + month.lengthOfMonth()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until (cells + 6) / 7) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 7) {
                                val n = row * 7 + col - offset + 1
                                Box(Modifier.weight(1f)) {
                                    if (n in 1..month.lengthOfMonth()) {
                                        val date = month.atDay(n)
                                        val day = byDate[date]
                                        DayCell(
                                            number = n,
                                            driving = day?.minutes(ActivityType.DRIVING),
                                            worked = day?.isWorked == true,
                                            severity = if (day != null) a.worstOn(date) else Severity.OK,
                                            isToday = date == today,
                                            onClick = if (day != null) ({ onOpenDay(date) }) else null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot(severityColor(Severity.INFRACTION), "Infraction")
                    LegendDot(severityColor(Severity.WARNING), "À vérifier")
                }
            }
        }
        item {
            val days = history.localDays.filter { YearMonth.from(it.date) == month }
            val findings = a.findings.filter { YearMonth.from(a.localDate(it.at)) == month }
            Panel(title = "Total du mois") {
                Row(Modifier.fillMaxWidth()) {
                    Metric("Jours travaillés", days.count { it.isWorked }.toString(), Modifier.weight(1f))
                    Metric("Conduite", hm(days.sumOf { it.minutes(ActivityType.DRIVING) }), Modifier.weight(1f))
                    Metric("Service", hm(days.sumOf { it.serviceMinutes }), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Distance", "${days.sumOf { it.distanceKm }} km", Modifier.weight(1f))
                    Metric("Nuit (21h–6h)", hm(days.sumOf { a.nightMinutes(it.periods) }), Modifier.weight(1f))
                    Metric(
                        "Infractions", findings.count { it.severity == Severity.INFRACTION }.toString(), Modifier.weight(1f),
                        color = if (findings.any { it.severity == Severity.INFRACTION }) severityColor(Severity.INFRACTION) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(number: Int, driving: Long?, worked: Boolean, severity: Severity, isToday: Boolean, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().aspectRatio(0.82f)
            .border(if (isToday) 1.5.dp else 1.dp, if (isToday) scheme.primary else scheme.outlineVariant, RoundedCornerShape(4.dp))
            .background(if (worked) scheme.primaryContainer.copy(alpha = 0.45f) else scheme.surface, RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(4.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            number.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (driving == null) scheme.onSurfaceVariant.copy(alpha = 0.5f) else scheme.onSurface,
        )
        if (worked && driving != null && driving > 0) {
            Text(hm(driving), fontSize = 10.sp, style = TabularNumbers, color = scheme.onSurface)
        }
        Box(
            Modifier.fillMaxWidth().height(3.dp).background(
                if (severity == Severity.OK) androidx.compose.ui.graphics.Color.Transparent else severityColor(severity),
                RoundedCornerShape(2.dp),
            ),
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
        Text("  $label", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
