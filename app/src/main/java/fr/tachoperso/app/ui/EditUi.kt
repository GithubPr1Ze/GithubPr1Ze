package fr.tachoperso.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.tachoperso.core.rules.DayOverride
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.EmployerDay
import fr.tachoperso.core.rules.EmployerExpenses
import fr.tachoperso.core.rules.NightStatus
import fr.tachoperso.core.rules.Rates
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val rowDate = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE)

/** Liste des repos « à préciser », corrigeables directement (Domicile / Découché). */
@Composable
fun ClarifyScreen(history: DriverHistory, onSetNight: (Instant, NightStatus?) -> Unit, onOpenDay: (LocalDate) -> Unit) {
    val list = history.nightsToClarify
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Repos à préciser", style = MaterialTheme.typography.titleLarge)
            Text(
                if (list.isEmpty()) "Tous les repos sont classés."
                else "${list.size} repos dont le lieu ne permet pas de savoir si vous étiez au domicile. Indiquez-le : " +
                    "découchés et frais sont recalculés aussitôt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(list, key = { it.rest.start.epochSecond }) { n ->
            Column {
                NightPanel(n, onSetNight)
                TextButton(onClick = { onOpenDay(n.date) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("Voir la journée du ${rowDate.format(n.date)}")
                }
            }
        }
    }
}

/** Édition jour par jour des repas et découchers d'un mois (mode employeur). */
@Composable
fun MonthDaysEditor(
    history: DriverHistory,
    rates: List<Rates>,
    markupPercent: Double,
    overrides: Map<LocalDate, DayOverride>,
    month: YearMonth,
    onSet: (LocalDate, DayOverride?) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    val days = EmployerExpenses.months(history.employerExpenses(rates, markupPercent, overrides)).firstOrNull { it.month == month }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Repas et découchers — ${monthFmt.format(month)}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Touchez une case pour l'ajouter ou la retirer. « Auto » revient au calcul d'après la carte.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (days != null) {
                Text(
                    "Total : ${days.dejeuners} déj. · ${days.diners} dîn. · ${days.petitsDejeuners} p.-déj. · ${days.decouchers} découch. · ${eur(days.total)}",
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        items(days?.days?.sortedBy { it.shift.start } ?: emptyList(), key = { it.shift.start.epochSecond }) { d ->
            DayEditRow(d, overrides[d.date] ?: DayOverride(), { onSet(d.date, it) }, { onOpenDay(d.date) })
        }
    }
}

@Composable
private fun DayEditRow(d: EmployerDay, o: DayOverride, onSet: (DayOverride?) -> Unit, onOpen: () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Text(
                rowDate.format(d.date).replaceFirstChar { it.titlecase(Locale.FRANCE) } + "  ${d.shift.start.localTime()} – ${d.shift.end.localTime()}",
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                modifier = Modifier.weight(1f),
            )
            Text(eur(d.total), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fun set(x: DayOverride) = onSet(x.takeIf { !it.isEmpty })
            Toggle("Déj.", d.dejeuner, o.dejeuner != null) { set(o.copy(dejeuner = !d.dejeuner)) }
            Toggle("Dîn.", d.diner, o.diner != null) { set(o.copy(diner = !d.diner)) }
            Toggle("P.-déj.", d.petitDejeuner, o.petitDejeuner != null) { set(o.copy(petitDejeuner = !d.petitDejeuner)) }
            Toggle("Découch.", d.decoucher, o.decoucher != null) { set(o.copy(decoucher = !d.decoucher)) }
        }
        if (d.edited) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Modifié à la main", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { onSet(null) }) { Text("Auto") }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, edited: Boolean, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    val text = @Composable { Text(label + if (edited) " *" else "", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
    if (on) Button(onClick = onClick, contentPadding = pad) { text() } else OutlinedButton(onClick = onClick, contentPadding = pad) { text() }
}
