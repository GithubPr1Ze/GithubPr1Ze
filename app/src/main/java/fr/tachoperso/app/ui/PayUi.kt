package fr.tachoperso.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.tachoperso.app.PaySettings
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.EmployerExpenses
import fr.tachoperso.core.rules.EmployerMonth
import fr.tachoperso.core.rules.PayslipCounts
import fr.tachoperso.core.rules.Rates
import fr.tachoperso.core.rules.Severity
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private fun YearMonth.title() = monthFmt.format(this).replaceFirstChar { it.titlecase(Locale.FRANCE) }
private fun num(s: String): Double? = s.trim().replace(" ", "").replace(",", ".").toDoubleOrNull()
private fun fmt(x: Double): String = if (x == Math.floor(x)) "%.0f".format(Locale.FRANCE, x) else "%.2f".format(Locale.FRANCE, x)

/** Net à payer estimé : net social fixe − impôt à la source + frais estimés. */
private fun netEstimate(pay: PaySettings, frais: Double): Double? =
    pay.netSocial?.let { it - (pay.incomeTax ?: 0.0) + frais }

/** Tableau de bord : forfait d'heures, nuit, frais estimés et net du mois. */
@Composable
fun PaySummaryPanel(history: DriverHistory, rates: List<Rates>, pay: PaySettings, lastDate: LocalDate, onOpen: () -> Unit) {
    val month = YearMonth.from(lastDate)
    val emp = EmployerExpenses.months(history.employerExpenses(rates, pay.markupPercent)).firstOrNull { it.month == month }
    Panel(title = "Paye de ${month.title().lowercase(Locale.FRANCE)} (estimation)") {
        Gauge("Temps de service / forfait", history.monthService(month), (pay.paidHours * 60).toLong(),
            note = "Forfait de ${fmt(pay.paidHours)} h payées quel que soit le temps réel.")
        Gauge("Heures de nuit (21 h – 6 h) / payées", history.monthNight(month), (pay.paidNightHours * 60).toLong())
        Row(Modifier.fillMaxWidth()) {
            Metric("Frais estimés", eur(emp?.total ?: 0.0), Modifier.weight(1f))
            Metric("Net estimé", netEstimate(pay, emp?.total ?: 0.0)?.let { eur(it) } ?: "—", Modifier.weight(1f))
        }
        if (pay.netSocial == null) {
            Text("Renseignez votre net social dans « Ma paye » pour estimer le net.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("Ma paye") }
    }
}

/** Écran « Ma paye » : réglages, puis pour chaque mois forfait, nuit, frais et comparaison au bulletin. */
@Composable
fun PayScreen(
    history: DriverHistory,
    rates: List<Rates>,
    pay: PaySettings,
    payslips: Map<YearMonth, PayslipCounts>,
    abroadMarkup: Boolean,
    onSetPay: (PaySettings) -> Unit,
    onSetPayslip: (YearMonth, PayslipCounts?) -> Unit,
) {
    val months = EmployerExpenses.months(history.employerExpenses(rates, pay.markupPercent))
    val protocol = fr.tachoperso.core.rules.Expenses.months(history.expenses(rates, abroadMarkup)).associateBy { it.month }
    var editing by remember { mutableStateOf<YearMonth?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ma paye", style = MaterialTheme.typography.titleLarge) }
        item { PaySettingsPanel(pay, onSetPay) }
        months.forEach { m ->
            item {
                MonthPayPanel(history, m, pay, payslips[m.month], protocol[m.month]?.total) { editing = m.month }
            }
        }
        item {
            Text(
                "Frais « pratique employeur » : déjeuner, dîner et petit-déjeuner chaque jour travaillé, un découcher chaque soir " +
                    "sauf avant un repos hebdomadaire au domicile ; taux du protocole majorés de ${fmt(pay.markupPercent)} %. " +
                    "Données saisies conservées uniquement sur ce téléphone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    editing?.let { m ->
        PayslipDialog(m, payslips[m], onDismiss = { editing = null }) { onSetPayslip(m, it); editing = null }
    }
}

@Composable
private fun PaySettingsPanel(pay: PaySettings, onSave: (PaySettings) -> Unit) {
    var hours by remember(pay) { mutableStateOf(fmt(pay.paidHours)) }
    var night by remember(pay) { mutableStateOf(fmt(pay.paidNightHours)) }
    var net by remember(pay) { mutableStateOf(pay.netSocial?.let { fmt(it) } ?: "") }
    var tax by remember(pay) { mutableStateOf(pay.incomeTax?.let { fmt(it) } ?: "") }
    var markup by remember(pay) { mutableStateOf(fmt(pay.markupPercent)) }
    Panel(title = "Mes réglages (d'après le bulletin)") {
        NumberField("Heures payées par mois", hours) { hours = it }
        NumberField("Heures de nuit payées par mois", night) { night = it }
        NumberField("Net social hors frais (€)", net) { net = it }
        NumberField("Impôt prélevé à la source (€ / mois)", tax) { tax = it }
        NumberField("Majoration des frais à l'étranger (%)", markup) { markup = it }
        Button(onClick = {
            onSave(
                PaySettings(
                    paidHours = num(hours) ?: pay.paidHours,
                    paidNightHours = num(night) ?: pay.paidNightHours,
                    netSocial = num(net),
                    incomeTax = num(tax),
                    markupPercent = num(markup) ?: pay.markupPercent,
                ),
            )
        }) { Text("Enregistrer") }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MonthPayPanel(
    history: DriverHistory,
    m: EmployerMonth,
    pay: PaySettings,
    slip: PayslipCounts?,
    protocolTotal: Double?,
    onEditSlip: () -> Unit,
) {
    val service = history.monthService(m.month)
    val paid = (pay.paidHours * 60).toLong()
    Panel(title = m.month.title()) {
        Gauge("Temps de service / forfait", service, paid,
            note = if (service > paid) "Au-delà du forfait : heures non couvertes à vérifier sur le bulletin."
            else "Marge sous le forfait : ${fr.tachoperso.core.rules.hm(paid - service)}.")
        Gauge("Heures de nuit (21 h – 6 h) / payées", history.monthNight(m.month), (pay.paidNightHours * 60).toLong())
        history.ferryMonths.firstOrNull { it.month == m.month }?.let {
            KeyValue("Ferry / train en repos", fr.tachoperso.core.rules.hm(it.restMinutes))
        }
        val r = m.days.firstOrNull()?.rates
        Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val st = MaterialTheme.typography.labelSmall
                val c = MaterialTheme.colorScheme.onSurfaceVariant
                Text("Frais (employeur)", style = st, color = c, modifier = Modifier.weight(1f))
                Text("Carte", style = st, color = c, modifier = Modifier.width(44.dp))
                Text("Bulletin", style = st, color = c, modifier = Modifier.width(56.dp))
                Text("Montant", style = st, color = c, modifier = Modifier.width(76.dp))
            }
            listOf(
                Triple("Déjeuners", m.dejeuners, slip?.dejeuners) to (r?.dejeuner ?: 0.0),
                Triple("Dîners", m.diners, slip?.diners) to (r?.diner ?: 0.0),
                Triple("Petits-déjeuners", m.petitsDejeuners, slip?.petitsDejeuners) to (r?.petitDejeuner ?: 0.0),
                Triple("Découchers", m.decouchers, slip?.decouchers) to (r?.decoucher ?: 0.0),
            ).forEach { (t, rate) ->
                val (label, card, onSlip) = t
                Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    val numStyle = MaterialTheme.typography.bodyMedium.merge(TabularNumbers)
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("$card", style = numStyle, modifier = Modifier.width(44.dp))
                    Text(
                        onSlip?.toString() ?: "—",
                        style = numStyle,
                        fontWeight = if (onSlip != null && onSlip != card) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            onSlip == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            onSlip < card -> severityColor(Severity.INFRACTION)
                            onSlip > card -> severityColor(Severity.OK)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.width(56.dp),
                    )
                    Text(eur(card * rate), style = numStyle, modifier = Modifier.width(76.dp))
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("Total estimé", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(eur(m.total), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
            }
        }
        if (slip != null) {
            val missing = listOf(
                "déjeuner(s)" to m.dejeuners - slip.dejeuners,
                "dîner(s)" to m.diners - slip.diners,
                "petit(s)-déjeuner(s)" to m.petitsDejeuners - slip.petitsDejeuners,
                "découcher(s)" to m.decouchers - slip.decouchers,
            ).filter { it.second > 0 }
            Text(
                if (missing.isEmpty()) "Bulletin conforme ou plus favorable que la carte."
                else "Le bulletin compte " + missing.joinToString(", ") { "${it.second} ${it.first}" } + " de moins que la carte : à vérifier.",
                style = MaterialTheme.typography.bodySmall,
                color = if (missing.isEmpty()) severityColor(Severity.OK) else severityColor(Severity.WARNING),
            )
        }
        protocolTotal?.let { KeyValue("Minimum légal (protocole)", eur(it)) }
        netEstimate(pay, m.total)?.let { KeyValue("Net à payer estimé", eur(it)) }
        TextButton(onClick = onEditSlip, contentPadding = PaddingValues(0.dp)) {
            Text(if (slip == null) "Saisir mon bulletin" else "Modifier mon bulletin")
        }
    }
}

@Composable
private fun PayslipDialog(month: YearMonth, current: PayslipCounts?, onDismiss: () -> Unit, onSave: (PayslipCounts?) -> Unit) {
    var dej by remember { mutableStateOf(current?.dejeuners?.toString() ?: "") }
    var din by remember { mutableStateOf(current?.diners?.toString() ?: "") }
    var dec by remember { mutableStateOf(current?.decouchers?.toString() ?: "") }
    var pdj by remember { mutableStateOf(current?.petitsDejeuners?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bulletin de ${month.title().lowercase(Locale.FRANCE)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Quantités indiquées sur le bulletin :", style = MaterialTheme.typography.bodySmall)
                NumberField("Déjeuners", dej) { dej = it }
                NumberField("Repas du soir", din) { din = it }
                NumberField("Découchers", dec) { dec = it }
                NumberField("Petits-déjeuners", pdj) { pdj = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = listOf(dej, din, dec, pdj).map { num(it)?.toInt() }
                onSave(if (c.all { it != null }) PayslipCounts(c[0]!!, c[1]!!, c[2]!!, c[3]!!) else null)
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
