package fr.tachoperso.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.tachoperso.app.LocalPlaceNames
import fr.tachoperso.app.placeName
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.Night
import fr.tachoperso.core.rules.NightStatus
import fr.tachoperso.core.rules.hm
import fr.tachoperso.core.tacho.GeoPoint
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

private fun NightStatus?.badge(): Pair<Color, String> = when (this) {
    NightStatus.AWAY -> Color(0xFF3B6EA8) to "Découché"
    NightStatus.HOME -> Color(0xFF3E9A6B) to "Domicile"
    null -> Color(0xFF8A8F98) to "À préciser"
}

/** Repos de la journée : lieu, statut domicile / découché, correction manuelle. */
@Composable
fun NightPanel(night: Night, onSet: (Instant, NightStatus?) -> Unit) {
    val name = placeName(night.point, LocalPlaceNames.current)
    Panel(title = if (night.weekly) "Repos hebdomadaire" else "Repos journalier") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${night.rest.start.local()} → ${night.rest.end.local()}  ·  ${hm(night.rest.minutes)}",
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                modifier = Modifier.weight(1f),
            )
            val (color, label) = night.status.badge()
            TagBadge(label, color)
        }
        Text(
            listOfNotNull(
                name ?: night.point?.label() ?: "Lieu inconnu",
                night.uncertaintyKm?.let { if (it < 0.5) "lieu exact" else "à ${it.toInt()} km près" },
                if (night.onFerry) "à bord d'un ferry/train" else null,
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (night.override != null) {
            Text(
                "Corrigé à la main (calcul automatique : ${night.autoStatus.badge().second.lowercase(Locale.FRANCE)}).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Domicile", night.status == NightStatus.HOME) { onSet(night.rest.start, NightStatus.HOME) }
            ChoiceButton("Découché", night.status == NightStatus.AWAY) { onSet(night.rest.start, NightStatus.AWAY) }
            if (night.override != null) {
                TextButton(onClick = { onSet(night.rest.start, null) }) { Text("Auto") }
            }
        }
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
    if (selected) Button(onClick = onClick, contentPadding = pad) { Text(label) }
    else OutlinedButton(onClick = onClick, contentPadding = pad) { Text(label) }
}

/** Tableau de bord : découchés du mois et domicile retenu. */
@Composable
fun NightsSummaryPanel(history: DriverHistory, lastDate: LocalDate, onSetHome: (GeoPoint?) -> Unit, onOpenClarify: () -> Unit = {}) {
    var choose by remember { mutableStateOf(false) }
    val names = LocalPlaceNames.current
    val months = history.nightMonths
    val current = months.firstOrNull { it.month.year == lastDate.year && it.month.monthValue == lastDate.monthValue }
    val previous = months.firstOrNull { it.month == java.time.YearMonth.from(lastDate).minusMonths(1) }
    Panel(title = "Découchés") {
        Row(Modifier.fillMaxWidth()) {
            Metric("Ce mois-ci", "${current?.away ?: 0}", Modifier.weight(1f))
            Metric("Mois dernier", "${previous?.away ?: 0}", Modifier.weight(1f))
            Metric(
                "À préciser ›",
                "${history.nights.count { it.status == null }}",
                Modifier.weight(1f).clickable(onClick = onOpenClarify),
                color = if (history.nights.any { it.status == null }) MaterialTheme.colorScheme.primary else null,
            )
        }
        Column {
            months.take(4).forEachIndexed { i, m ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(monthFmt.format(m.month).replaceFirstChar { it.titlecase(Locale.FRANCE) },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${m.away} découché(s)", style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                    if (m.unknown > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text("· ${m.unknown} à préciser", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val home = history.home
        val homeName = placeName(home, names)
        val homeCount = history.restClusters.firstOrNull()?.weeklyRests ?: 0
        Text("Domicile", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when {
                home == null -> "inconnu"
                history.homeDeclared -> (homeName ?: home.label()) + " (choisi)"
                else -> (homeName ?: home.label()) + " (détecté, $homeCount repos hebdo)"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Lieu où le camion stationne pendant vos repos à la maison (domicile ou dépôt), rayon 2 km.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { choose = true }, contentPadding = PaddingValues(0.dp)) { Text("Changer le domicile") }
    }
    if (choose) HomeDialog(history, onDismiss = { choose = false }) { onSetHome(it); choose = false }
}

/** Choix du domicile parmi les lieux de repos hebdomadaires les plus fréquents. */
@Composable
private fun HomeDialog(history: DriverHistory, onDismiss: () -> Unit, onPick: (GeoPoint?) -> Unit) {
    val names = LocalPlaceNames.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Domicile") },
        text = {
            Column {
                Text("Lieux de vos repos hebdomadaires :", style = MaterialTheme.typography.bodyMedium)
                history.restClusters.take(6).forEach { c ->
                    val n = placeName(c.center, names)
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(c.center) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(n ?: c.center.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${c.weeklyRests} repos", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Divider()
                }
                if (history.restClusters.isEmpty()) {
                    Text("Aucun repos hebdomadaire localisé pour l'instant.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text("Automatique") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
