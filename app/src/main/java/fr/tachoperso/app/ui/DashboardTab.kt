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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.tachoperso.app.DriverProfile
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.Limits
import fr.tachoperso.core.rules.Severity
import fr.tachoperso.core.rules.hm
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

@Composable
fun DashboardTab(
    profile: DriverProfile,
    history: DriverHistory,
    onOpenDay: (LocalDate) -> Unit,
    onEditEmail: () -> Unit,
    onOpenFerry: () -> Unit = {},
    onSetHome: (fr.tachoperso.core.tacho.GeoPoint?) -> Unit = {},
    expenses: List<fr.tachoperso.core.rules.ShiftExpenses> = emptyList(),
    onOpenExpenses: () -> Unit = {},
    onExportPdf: (java.time.YearMonth) -> Unit = {},
) {
    val a = history.analysis
    val lastDate = history.localDays.firstOrNull()?.date ?: LocalDate.now(ZONE)
    val monday = a.monday(lastDate)
    val sunday = monday.plusDays(6)
    val since = lastDate.minusDays(27)
    val recent = a.findings.filter { !a.localDate(it.at).isBefore(since) }
    val lastShift = a.shifts.lastOrNull()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val reminders = fr.tachoperso.app.Reminders.due(profile)
        if (reminders.isNotEmpty()) {
            item { RemindersPanel(reminders) }
        }

        item { DriverPanel(profile, history, onEditEmail) }

        item {
            Panel(title = "Semaine du ${dayShort.format(monday)} au ${dayShort.format(sunday)}") {
                val week = a.weekDriving(monday)
                val service = a.weekService(monday)
                Gauge("Conduite hebdomadaire", week, Limits.WEEKLY_DRIVING.toLong())
                Gauge("Conduite sur deux semaines", a.fortnightDriving(monday), Limits.FORTNIGHT_DRIVING.toLong())
                Gauge(
                    "Temps de service", service, Limits.SERVICE_WEEKLY.toLong(),
                    note = "Au-delà de 35 h : ${hm((service - Limits.LEGAL_WEEKLY).coerceAtLeast(0))}  ·  " +
                        "au-delà de 43 h (équivalence) : ${hm((service - Limits.EQUIVALENCE_WEEKLY).coerceAtLeast(0))}",
                )
                KeyValue("Journées à plus de 9 h de conduite", "${a.extensionsUsed(monday)} / ${Limits.EXTENSIONS_PER_WEEK}")
            }
        }

        if (expenses.isNotEmpty()) {
            item { ExpensesSummaryPanel(fr.tachoperso.core.rules.Expenses.months(expenses), lastDate, onOpenExpenses) }
        }

        item { PdfPanel(history, onExportPdf) }

        if (history.nights.isNotEmpty()) {
            item { NightsSummaryPanel(history, lastDate, onSetHome) }
        }

        if (history.ferry.isNotEmpty()) {
            item { CrossingsPanel(history, monday, sunday, lastDate, onOpenFerry) }
        }

        if (lastShift != null) {
            item {
                Panel(title = "Dernière journée de travail") {
                    val day = a.localDate(lastShift.start)
                    Text(
                        "${day.long()}  ·  ${lastShift.start.localTime()} – ${lastShift.end.localTime()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Conduite", hm(lastShift.driving), Modifier.weight(1f))
                        Metric("Service", hm(lastShift.service), Modifier.weight(1f))
                        Metric("Amplitude", hm(lastShift.amplitude), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Nuit (21h–6h)", hm(a.nightMinutes(lastShift.periods)), Modifier.weight(1f))
                        Metric("Pauses", hm(lastShift.breaks), Modifier.weight(1f))
                        Metric("Repos suivant", lastShift.restAfter?.let { hm(it.minutes) } ?: "en cours", Modifier.weight(1f))
                    }
                    TextButton(onClick = { onOpenDay(day) }, contentPadding = PaddingValues(0.dp)) { Text("Voir le détail") }
                }
            }
        }

        item {
            val infractions = recent.count { it.severity == Severity.INFRACTION }
            val warnings = recent.count { it.severity == Severity.WARNING }
            Panel(
                title = "Conformité — 28 derniers jours",
                trailing = {
                    SeverityBadge(
                        when {
                            infractions > 0 -> Severity.INFRACTION
                            warnings > 0 -> Severity.WARNING
                            else -> Severity.OK
                        },
                        when {
                            infractions > 0 -> "$infractions infraction(s)"
                            warnings > 0 -> "$warnings à vérifier"
                            else -> "Conforme"
                        },
                    )
                },
            ) {
                if (recent.isEmpty()) {
                    Text("Aucun constat sur la période.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column {
                        recent.take(6).forEachIndexed { i, f ->
                            if (i > 0) Divider()
                            FindingRow(f, showDate = true) { onOpenDay(a.localDate(f.at)) }
                        }
                    }
                    if (recent.size > 6) {
                        Text(
                            "${recent.size - 6} autre(s) constat(s) : voir le calendrier.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            val q = a.quarterStart(lastDate)
            Panel(title = "Trimestre en cours") {
                Gauge(
                    "Temps de service du ${dateFmt.format(q)}", a.quarterService(lastDate), Limits.SERVICE_QUARTER.toLong(),
                    note = "Plafond trimestriel grands routiers (moyenne de 53 h par semaine).",
                )
            }
        }

        item {
            Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DriverPanel(profile: DriverProfile, history: DriverHistory, onEditEmail: () -> Unit) {
    Panel(title = "Conducteur") {
        Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
        KeyValue("N° de carte", profile.cardNumber)
        val expiry = profile.expiry
        Row(Modifier.fillMaxWidth()) {
            Text("Validité", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            // Date d'expiration enregistrée à 23:59:59 UTC : on l'affiche dans ce fuseau.
            Text(expiry?.let { dateFmt.format(it.atZone(java.time.ZoneOffset.UTC)) } ?: "—", style = MaterialTheme.typography.bodyMedium)
            if (expiry != null) {
                val days = Duration.between(Instant.now(), expiry).toDays()
                when {
                    days < 0 -> { Text("  "); SeverityBadge(Severity.INFRACTION, "Expirée") }
                    days < 90 -> { Text("  "); SeverityBadge(Severity.WARNING, "$days j restants") }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Email", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                profile.email.ifBlank { "Non renseigné" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (profile.email.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        KeyValue("Dernière lecture", profile.lastRead?.local() ?: "—")
        val first = history.localDays.lastOrNull()?.date
        val last = history.localDays.firstOrNull()?.date
        KeyValue("Données disponibles", if (first != null && last != null) "du ${dateFmt.format(first)} au ${dateFmt.format(last)}" else "—")
        TextButton(onClick = onEditEmail, contentPadding = PaddingValues(0.dp)) {
            Text(if (profile.email.isBlank()) "Ajouter un email" else "Modifier l'email")
        }
    }
}


/** Temps passé en mode ferry/train : semaine, mois, total et synthèse par mois. */
@Composable
private fun CrossingsPanel(history: DriverHistory, monday: LocalDate, sunday: LocalDate, lastDate: LocalDate, onOpen: () -> Unit) {
    val all = history.ferry
    val week = history.ferryBetween(monday, sunday)
    val month = history.ferryBetween(lastDate.withDayOfMonth(1), lastDate)
    Panel(title = "Ferry / train") {
        Row(Modifier.fillMaxWidth()) {
            Metric("Cette semaine", hm(week.sumOf { it.minutes }), Modifier.weight(1f))
            Metric("Ce mois-ci", hm(month.sumOf { it.minutes }), Modifier.weight(1f))
            Metric("Total", hm(all.sumOf { it.minutes }), Modifier.weight(1f))
        }
        Text(
            "${all.size} traversée(s) depuis le ${dateFmt.format(all.first().from.atZone(ZONE))}, " +
                "dont ${hm(all.sumOf { it.restMinutes })} enregistrées en repos (hors temps de service).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FerryMonthTable(history.ferryMonths.take(4))
        TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("Synthèse mensuelle détaillée") }
    }
}

@Composable
private fun RemindersPanel(reminders: List<fr.tachoperso.app.Reminder>) {
    Panel(title = "Rappels") {
        Column {
            reminders.forEachIndexed { i, r ->
                if (i > 0) Divider()
                Column(Modifier.padding(vertical = 6.dp)) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        SeverityBadge(if (r.urgent) Severity.INFRACTION else Severity.WARNING, if (r.urgent) "Urgent" else "À prévoir")
                        Text("  " + r.title, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(r.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Export du récapitulatif mensuel PDF : un bouton par mois disponible (6 derniers). */
@Composable
private fun PdfPanel(history: DriverHistory, onExport: (java.time.YearMonth) -> Unit) {
    val months = history.localDays.map { java.time.YearMonth.from(it.date) }.distinct().sortedDescending().take(6)
    Panel(title = "Récapitulatif mensuel (PDF)") {
        Text(
            "Heures, semaines, frais de route, découchés et ferry du mois, jour par jour.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        months.chunked(3).forEach { row ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { onExport(m) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Text(java.time.format.DateTimeFormatter.ofPattern("MMM yy", Locale.FRANCE).format(m), maxLines = 1)
                    }
                }
            }
        }
    }
}
