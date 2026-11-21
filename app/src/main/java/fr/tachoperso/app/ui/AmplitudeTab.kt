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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.tachoperso.core.rules.Amplitude
import fr.tachoperso.core.rules.AmplitudeDay
import fr.tachoperso.core.rules.AmplitudeMonth
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.hm
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ampDate = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE)

/** Icône horloge (absente de material-icons-core), tracé Material « Schedule ». */
val ClockIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.material.icons.materialIcon("Clock") {
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes(
                "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
                    "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z" +
                    "M12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
            ),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }
}

private fun YearMonth.title() = monthFmt.format(this).replaceFirstChar { it.titlecase(Locale.FRANCE) }

/**
 * Onglet Amplitude : par journée de travail (prise → fin de poste), amplitude moins la coupure de
 * 45 min (si conduite > 4 h 30 ou amplitude > 6 h), récapitulatif mensuel.
 */
@Composable
fun AmplitudeTab(history: DriverHistory, paidHours: Double?, onOpenDay: (LocalDate) -> Unit) {
    val months = remember(history) { Amplitude.months(history.analysis) }
    var index by remember(history) { mutableStateOf(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (months.isEmpty()) {
            item { Text("Aucune journée de travail sur la carte.", style = MaterialTheme.typography.bodyMedium) }
            return@LazyColumn
        }
        val m = months[index.coerceIn(months.indices)]
        item {
            // Liste triée du plus récent au plus ancien : « précédent » = index + 1.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { index++ }, enabled = index < months.lastIndex) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
                }
                Text(m.month.title(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { index-- }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
                }
            }
        }
        // Écart avec le forfait payé : seulement pour un mois terminé et lu en entier (pas le premier mois lu).
        val complete = m.month < YearMonth.now(ZONE) && m != months.last()
        item { MonthSummary(m, paidHours.takeIf { complete }) }
        item {
            Panel(title = "Journées de travail") {
                AmplitudeHeader()
                Column {
                    m.days.forEach { d ->
                        Divider()
                        AmplitudeRow(d) { onOpenDay(d.date) }
                    }
                }
            }
        }
        item {
            Panel(title = "Tous les mois") {
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        val st = MaterialTheme.typography.labelSmall
                        val c = MaterialTheme.colorScheme.onSurfaceVariant
                        Text("Mois", style = st, color = c, modifier = Modifier.weight(1f))
                        Text("Jours", style = st, color = c, modifier = Modifier.width(44.dp))
                        Text("Amplitude", style = st, color = c, modifier = Modifier.width(70.dp))
                        Text("Retenu", style = st, color = c, modifier = Modifier.width(64.dp))
                    }
                    months.forEachIndexed { i, mm ->
                        Divider()
                        Row(Modifier.fillMaxWidth().clickable { index = i }.padding(vertical = 6.dp)) {
                            val num = MaterialTheme.typography.bodyMedium.merge(TabularNumbers)
                            Text(mm.month.title(), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (i == index) FontWeight.SemiBold else null, modifier = Modifier.weight(1f))
                            Text("${mm.count}", style = num, modifier = Modifier.width(44.dp))
                            Text(hm(mm.gross), style = num, modifier = Modifier.width(70.dp))
                            Text(hm(mm.net), style = num, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Amplitude = de la prise à la fin de poste, entre deux repos journaliers ; une journée qui passe minuit " +
                    "compte pour le jour (et le mois) de prise de poste. 45 min de coupure sont retirées si la conduite " +
                    "dépasse 4 h 30 ou l'amplitude 6 h. Le premier et le dernier mois lus peuvent être incomplets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthSummary(m: AmplitudeMonth, paidHours: Double?) {
    Panel(title = "Récapitulatif du mois") {
        Row(Modifier.fillMaxWidth()) {
            Metric("Journées", "${m.count}", Modifier.weight(1f))
            Metric("Amplitude", hm(m.gross), Modifier.weight(1f))
            Metric("Coupures", "−" + hm(m.deductions), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Metric("Retenu", hm(m.net), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
            Metric("Moyenne / jour", hm(m.average), Modifier.weight(1f))
            Metric("Plus longue", m.longest?.let { hm(it.amplitude) } ?: "—", Modifier.weight(1f))
        }
        if (paidHours != null && paidHours > 0) {
            val paid = Math.round(paidHours * 60)
            KeyValue("Heures payées (forfait)", hm(paid))
            KeyValue("Écart retenu − payé", (if (m.net - paid > 0) "+" else "") + hm(m.net - paid))
        }
    }
}

@Composable
private fun AmplitudeHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val st = MaterialTheme.typography.labelSmall
        val c = MaterialTheme.colorScheme.onSurfaceVariant
        Text("Prise de poste", style = st, color = c, modifier = Modifier.weight(1f))
        Text("Cond.", style = st, color = c, modifier = Modifier.width(48.dp))
        Text("Ampl.", style = st, color = c, modifier = Modifier.width(48.dp))
        Text("Retenu", style = st, color = c, modifier = Modifier.width(56.dp))
    }
}

@Composable
private fun AmplitudeRow(d: AmplitudeDay, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val num = MaterialTheme.typography.bodyMedium.merge(TabularNumbers)
        Column(Modifier.weight(1f)) {
            Text(ampDate.format(d.date).replaceFirstChar { it.titlecase(Locale.FRANCE) }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${d.shift.start.localTime()} – ${d.shift.end.localTime()}" +
                    (if (d.inProgress) " · en cours" else "") +
                    (if (d.deduction == 0L) " · sans coupure" else ""),
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(hm(d.driving), style = num, modifier = Modifier.width(48.dp))
        Text(hm(d.amplitude), style = num, modifier = Modifier.width(48.dp))
        Text(hm(d.net), style = num, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
    }
}
