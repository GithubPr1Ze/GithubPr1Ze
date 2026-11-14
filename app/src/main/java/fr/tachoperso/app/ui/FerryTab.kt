package fr.tachoperso.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.FerryCrossing
import fr.tachoperso.core.rules.FerryMonth
import fr.tachoperso.core.rules.Severity
import fr.tachoperso.core.rules.hm
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val rowDate = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE)

private fun FerryMonth.title(): String = monthFmt.format(month).replaceFirstChar { it.titlecase(Locale.FRANCE) }

/** Synthèse mensuelle des traversées ferry/train et du temps passé en repos à bord. */
@Composable
fun FerryScreen(history: DriverHistory, onOpenDay: (LocalDate) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ferry / train", style = MaterialTheme.typography.titleLarge) }
        item {
            Panel(title = "Synthèse par mois") {
                FerryMonthTable(history.ferryMonths)
                Text(
                    "« En repos » : temps de traversée enregistré en repos, donc hors temps de service " +
                        "(non compté dans les heures payées).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        history.ferryMonths.forEach { m ->
            item {
                Panel(title = m.title()) {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Traversées", "${m.count}", Modifier.weight(1f))
                        Metric("Durée", hm(m.minutes), Modifier.weight(1f))
                        Metric("En repos", hm(m.restMinutes), Modifier.weight(1f))
                    }
                    if (m.estimatedCount > 0) {
                        Text(
                            "Dont ${m.estimatedCount} traversée(s) estimée(s) : ${hm(m.estimatedMinutes)}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        m.crossings.forEachIndexed { i, c ->
                            if (i > 0) Divider()
                            FerryRow(c, showDate = true, onClick = { onOpenDay(c.from.atZone(ZONE).toLocalDate()) })
                        }
                    }
                    if (m.estimatedCount > 0) FerryAdvice()
                }
            }
        }
        item {
            Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Tableau mois / traversées / durée / en repos. */
@Composable
fun FerryMonthTable(months: List<FerryMonth>) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val st = MaterialTheme.typography.labelSmall
            val c = MaterialTheme.colorScheme.onSurfaceVariant
            Text("Mois", style = st, color = c, modifier = Modifier.weight(1f))
            Text("Trav.", style = st, color = c, modifier = Modifier.width(44.dp))
            Text("Durée", style = st, color = c, modifier = Modifier.width(60.dp))
            Text("En repos", style = st, color = c, modifier = Modifier.width(64.dp))
        }
        months.forEach { m ->
            Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                val num = MaterialTheme.typography.bodyMedium.merge(TabularNumbers)
                Text(m.title(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${m.count}", style = num, modifier = Modifier.width(44.dp))
                Text(hm(m.minutes), style = num, modifier = Modifier.width(60.dp))
                Text(hm(m.restMinutes), style = num, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
            }
        }
    }
}

/** Une traversée : horaires, durée (mesurée ou estimée), temps en repos. */
@Composable
fun FerryRow(c: FerryCrossing, showDate: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (showDate) rowDate.format(c.from.atZone(ZONE)).replaceFirstChar { it.titlecase(Locale.FRANCE) } + "  " else "") +
                    c.from.localTime() + (c.to?.let { " – ${it.localTime()}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            )
            val note = when {
                c.estimated -> "Estimée : mode coupé après ${c.cutAfterSeconds} s, durée du repos qui suit"
                !c.known -> "Durée inconnue"
                c.restMinutes < c.minutes -> "Dont ${hm(c.restMinutes)} en repos"
                else -> null
            }
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (c.estimated) {
            SeverityBadge(Severity.WARNING, "estimée")
            Text("  ")
        }
        Text(
            if (c.known) (if (c.estimated) "≈ " else "") + hm(c.minutes) else "—",
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
        )
    }
}

/** Conseil affiché quand une traversée a dû être estimée. */
@Composable
fun FerryAdvice() {
    Text(
        "Mode ferry/train coupé au passage automatique en repos : activez-le une fois le contact coupé et le " +
            "chronotachygraphe passé en repos. Sans traversée enregistrée, l'exception de l'art. 9 du règl. " +
            "(CE) 561/2006 (repos interrompu à bord) ne sera pas visible lors d'un contrôle.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
