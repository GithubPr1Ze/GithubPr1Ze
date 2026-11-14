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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.tachoperso.app.BaremeRepository
import fr.tachoperso.core.rules.ExpenseMonth
import fr.tachoperso.core.rules.Expenses
import fr.tachoperso.core.rules.ShiftExpenses
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val euro: NumberFormat = NumberFormat.getCurrencyInstance(Locale.FRANCE)
fun eur(x: Double): String = euro.format(x)

private val rowDate = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE)
private fun YearMonth.title() = monthFmt.format(this).replaceFirstChar { it.titlecase(Locale.FRANCE) }

private fun ShiftExpenses.summary(): String =
    items.joinToString(" + ") { (if (it.count > 1) "${it.count} × " else "") + it.kind.label.substringBefore(" (").lowercase(Locale.FRANCE) }
        .ifBlank { "aucune indemnité" }

/** Tableau de bord : frais du mois en cours et des mois précédents. */
@Composable
fun ExpensesSummaryPanel(months: List<ExpenseMonth>, lastDate: LocalDate, onOpen: () -> Unit) {
    val current = months.firstOrNull { it.month == YearMonth.from(lastDate) }
    val previous = months.firstOrNull { it.month == YearMonth.from(lastDate).minusMonths(1) }
    Panel(title = "Frais de route (estimation)") {
        Row(Modifier.fillMaxWidth()) {
            Metric("Ce mois-ci", eur(current?.total ?: 0.0), Modifier.weight(1f))
            Metric("Mois dernier", eur(previous?.total ?: 0.0), Modifier.weight(1f))
        }
        ExpenseMonthTable(months.take(4))
        TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("Détail des frais") }
    }
}

@Composable
private fun ExpenseMonthTable(months: List<ExpenseMonth>) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val st = MaterialTheme.typography.labelSmall
            val c = MaterialTheme.colorScheme.onSurfaceVariant
            Text("Mois", style = st, color = c, modifier = Modifier.weight(1f))
            Text("Repas", style = st, color = c, modifier = Modifier.width(48.dp))
            Text("Découch.", style = st, color = c, modifier = Modifier.width(60.dp))
            Text("Total", style = st, color = c, modifier = Modifier.width(84.dp))
        }
        months.forEach { m ->
            Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                val num = MaterialTheme.typography.bodyMedium.merge(TabularNumbers)
                Text(m.month.title(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${m.meals}", style = num, modifier = Modifier.width(48.dp))
                Text("${m.decouchers}", style = num, modifier = Modifier.width(60.dp))
                Text(eur(m.total), style = num, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(84.dp))
            }
        }
    }
}

/** Écran détaillé : barème utilisé, option étranger, frais jour par jour, par mois. */
@Composable
fun ExpensesScreen(
    expenses: List<ShiftExpenses>,
    bareme: BaremeRepository.Bareme?,
    abroadMarkup: Boolean,
    onAbroadMarkup: (Boolean) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    val months = Expenses.months(expenses)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Frais de route", style = MaterialTheme.typography.titleLarge) }
        item {
            Panel(title = "Barème") {
                val r = bareme?.rates?.lastOrNull()
                if (r == null) {
                    Text("Barème indisponible.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("${r.source}, en vigueur depuis le ${dateFmt.format(r.effective)}", style = MaterialTheme.typography.bodyMedium)
                    KeyValue("Repas", eur(r.repas))
                    KeyValue("Casse-croûte", eur(r.casseCroute))
                    KeyValue("Grand déplacement 1 repas", eur(r.grandDeplacement1Repas))
                    KeyValue("Grand déplacement 2 repas", eur(r.grandDeplacement2Repas))
                    KeyValue("Casse-croûte de nuit", eur(r.repasUniqueNuit))
                    Text("Barème ${bareme.origin}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Majoration étranger +18 %", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Protocole art. 13 : seulement à défaut d'accord d'entreprise ou de clause du contrat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = abroadMarkup, onCheckedChange = onAbroadMarkup)
                }
            }
        }
        months.forEach { m ->
            item {
                Panel(title = m.month.title()) {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Total", eur(m.total), Modifier.weight(1.4f))
                        Metric("Repas", "${m.meals}", Modifier.weight(1f))
                        Metric("Découchers", "${m.decouchers}", Modifier.weight(1f))
                    }
                    if (m.toClarify > 0) {
                        Text(
                            "${m.toClarify} journée(s) dont le repos est « à préciser » : comptées sans découcher.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        m.days.sortedBy { it.shift.start }.forEachIndexed { i, d ->
                            if (i > 0) Divider()
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenDay(d.date) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(rowDate.format(d.date).replaceFirstChar { it.titlecase(Locale.FRANCE) } +
                                        "  ${d.shift.start.localTime()} – ${d.shift.end.localTime()}",
                                        style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                                    Text(
                                        d.summary() + (if (d.abroad) " · étranger" else "") + (if (d.nightToClarify) " · repos à préciser" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(eur(d.total), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Estimation selon le protocole du 30/04/1974 (CCN transports routiers, IDCC 16). Les indemnités sont réduites " +
                    "si l'employeur prend en charge repas ou logement (art. 14) ; la couchette du camion n'est pas un logement. " +
                    "Un accord d'entreprise plus favorable peut s'appliquer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Détail du jour : indemnités de la journée de travail. */
@Composable
fun DayExpensesPanel(e: ShiftExpenses) {
    Panel(title = "Frais de route — journée du ${e.shift.start.localTime()}") {
        if (e.items.isEmpty()) {
            Text("Aucune indemnité pour ce service.", style = MaterialTheme.typography.bodyMedium)
        }
        e.items.forEach { it ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text((if (it.count > 1) "${it.count} × " else "") + it.kind.label, style = MaterialTheme.typography.bodyMedium)
                    Text(it.kind.article, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(eur(it.amount), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
            }
        }
        Divider()
        Row(Modifier.fillMaxWidth()) {
            Text("Total", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(eur(e.total), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${e.meals} période(s) de repas couverte(s) · repos suivant : " +
                when (e.awayNight) {
                    fr.tachoperso.core.rules.NightStatus.AWAY -> "découché"
                    fr.tachoperso.core.rules.NightStatus.HOME -> "domicile"
                    null -> "à préciser (compté sans découcher)"
                } + (if (e.abroad) " · majoration étranger +18 %" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
