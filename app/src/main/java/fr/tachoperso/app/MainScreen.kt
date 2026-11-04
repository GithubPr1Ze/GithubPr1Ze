package fr.tachoperso.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.tachoperso.core.tacho.ActivityType
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.DailyActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRENCH)
private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH)

private fun Instant?.local(withTime: Boolean = true): String =
    this?.atZone(ZoneId.systemDefault())?.let { (if (withTime) dateTimeFormat else dateFormat).format(it) } ?: "—"

private fun hm(minutes: Int): String = "%dh%02d".format(minutes / 60, minutes % 60)

private fun activityColor(type: ActivityType): Color = when (type) {
    ActivityType.DRIVING -> Color(0xFFE5533D)
    ActivityType.WORK -> Color(0xFFF2A93B)
    ActivityType.AVAILABILITY -> Color(0xFF4C8BF5)
    ActivityType.REST -> Color(0xFF3BB273)
}

private val cardOutColor = Color(0xFF9E9E9E)

@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showLogs by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDdd(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) vm.exportDdd(uri) }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Tacho Perso", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    state.readerName?.let { "Lecteur détecté : $it" } ?: "Aucun lecteur détecté",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Section {
                    Button(onClick = vm::readCard, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.busy) "Lecture en cours…" else "Lire la carte")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            enabled = !state.busy, modifier = Modifier.weight(1f),
                        ) { Text("Ouvrir .DDD") }
                        OutlinedButton(
                            onClick = { exportLauncher.launch(state.report?.suggestedFileName() ?: "carte.DDD") },
                            enabled = !state.busy && state.report != null, modifier = Modifier.weight(1f),
                        ) { Text("Exporter .DDD") }
                    }
                    state.progress?.let { p ->
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            state.report?.let { report ->
                item { IdentitySection(report, state.source) }
                if (report.dump.warnings.isNotEmpty()) {
                    item {
                        Section(title = "Avertissements") {
                            report.dump.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                item {
                    Text(
                        "Activités (${report.days.size} jours, heures UTC)",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Legend()
                }
                items(report.days, key = { it.date.toString() }) { day -> DayCard(day) }
            }

            if (state.history.isNotEmpty()) {
                item {
                    Section(title = "Lectures enregistrées (${state.history.size})") {
                        TextButton(onClick = { showHistory = !showHistory }) {
                            Text(if (showHistory) "Masquer" else "Afficher")
                        }
                        if (showHistory) {
                            state.history.forEach { name ->
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().clickable { vm.openHistory(name) }.padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Section(title = "Journal technique") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Masquer" else "Afficher") }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(vm.logText())) }) { Text("Copier") }
                    }
                    if (showLogs) {
                        Text(
                            state.logs.joinToString("\n").ifEmpty { "(vide)" },
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String? = null, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IdentitySection(report: CardReport, source: String?) {
    Section(title = "Carte conducteur") {
        val id = report.identity
        Field("Nom", id?.surname ?: "")
        Field("Prénoms", id?.firstNames ?: "")
        Field("Né(e) le", id?.birthDate?.format(dateFormat) ?: "")
        Field("N° de carte", id?.cardNumber ?: "")
        Field("Délivrée par", listOfNotNull(id?.issuingAuthority, id?.issuingNation).joinToString(" – "))
        Field("Validité", "${id?.validityBegin.local(false)} → ${id?.expiryDate.local(false)}")
        Field("Permis", report.licence?.let { "${it.number} (${it.nation})" } ?: "")
        Field("Génération", report.generationLabel)
        Field("Dernier télécharg.", report.lastCardDownload.local())
        Field(
            "Dernier véhicule",
            report.currentUsage?.let { "${it.vehicleRegistration} (${it.vehicleNation}) le ${it.sessionOpenTime.local()}" } ?: "",
        )
        HorizontalDivider()
        Field("Fichiers lus", "${report.dump.files.size} dont ${report.signedFiles} signés")
        if (source != null) Field("Source", source)
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        listOf(
            "Conduite" to activityColor(ActivityType.DRIVING),
            "Travail" to activityColor(ActivityType.WORK),
            "Dispo" to activityColor(ActivityType.AVAILABILITY),
            "Repos" to activityColor(ActivityType.REST),
            "Hors carte" to cardOutColor,
        ).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DayCard(day: DailyActivity) {
    Section {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dayFormat.format(day.date).replaceFirstChar { it.titlecase(Locale.FRENCH) }, fontWeight = FontWeight.SemiBold)
            Text("${day.distanceKm} km", style = MaterialTheme.typography.bodyMedium)
        }
        DayTimeline(day)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Conduite", day.minutes(ActivityType.DRIVING))
            Stat("Travail", day.minutes(ActivityType.WORK))
            Stat("Dispo", day.minutes(ActivityType.AVAILABILITY))
            Stat("Repos", day.minutes(ActivityType.REST))
            if (day.minutesCardOut > 0) Stat("Hors carte", day.minutesCardOut)
        }
    }
}

@Composable
private fun Stat(label: String, minutes: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(hm(minutes), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayTimeline(day: DailyActivity) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        var cursor = 0
        for (segment in day.segments) {
            if (segment.startMinute > cursor) {
                Box(Modifier.weight((segment.startMinute - cursor).toFloat()).fillMaxHeight())
            }
            val color = if (segment.cardNotInserted) cardOutColor else activityColor(segment.type)
            Box(Modifier.weight(segment.duration.toFloat()).fillMaxHeight().background(color))
            cursor = segment.endMinute
        }
        if (cursor < DailyActivity.MINUTES_PER_DAY) {
            Box(Modifier.weight((DailyActivity.MINUTES_PER_DAY - cursor).toFloat()).fillMaxHeight())
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0h", "6h", "12h", "18h", "24h").forEach { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
