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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.Vehicle
import java.util.Locale

private fun km(x: Int) = "%,d km".format(Locale.FRANCE, x)

/** Tableau de bord : véhicule actuel et accès à la liste. */
@Composable
fun VehiclesSummaryPanel(history: DriverHistory, vehicleCompany: Map<String, String>, onOpen: () -> Unit) {
    val current = history.vehicles.firstOrNull() ?: return
    Panel(title = "Véhicules") {
        KeyValue("Véhicule actuel", "${current.registration} (${current.nation})")
        KeyValue("Société", vehicleCompany[current.key] ?: "non renseignée")
        KeyValue("Véhicules utilisés", "${history.vehicles.size}")
        TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("Véhicules et sociétés ›") }
    }
}

/** Liste des véhicules utilisés, affectation à une société, récapitulatif par société. */
@Composable
fun VehiclesScreen(
    history: DriverHistory,
    companies: List<String>,
    vehicleCompany: Map<String, String>,
    onAssign: (String, String?) -> Unit,
) {
    var choosing by remember { mutableStateOf<Vehicle?>(null) }
    val zone = history.zone
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Véhicules et sociétés", style = MaterialTheme.typography.titleLarge)
            Text(
                "Affectez chaque véhicule à la société pour laquelle vous l'avez conduit : les journées sont rattachées à la " +
                    "société du véhicule utilisé (récapitulatifs par société à venir).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val byCompany = history.vehicles.groupBy { vehicleCompany[it.key] }
        if (byCompany.keys.any { it != null }) {
            item {
                Panel(title = "Par société") {
                    Column {
                        byCompany.entries.sortedByDescending { e -> e.value.maxOf { it.lastUse } }.forEachIndexed { i, (c, vs) ->
                            if (i > 0) Divider()
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(c ?: "Non affectés", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "du ${dateFmt.format(vs.minOf { it.firstUse }.atZone(zone))} au ${dateFmt.format(vs.maxOf { it.lastUse }.atZone(zone))}" +
                                        "  ·  ${vs.size} véhicule(s)  ·  ${vs.sumOf { it.days(zone) }} jours  ·  ${km(vs.sumOf { it.km })}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        items(history.vehicles, key = { it.key }) { v ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${v.registration}  ·  ${v.nation}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(km(v.km), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                }
                Text(
                    "du ${dateFmt.format(v.firstUse.atZone(zone))} au ${dateFmt.format(v.lastUse.atZone(zone))}  ·  ${v.days(zone)} jours" +
                        (v.vin?.let { "  ·  VIN …${it.takeLast(6)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().clickable { choosing = v }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Société", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Text(vehicleCompany[v.key] ?: "Choisir ›", style = MaterialTheme.typography.bodyMedium,
                        color = if (vehicleCompany[v.key] == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            Text(
                "La carte garde environ 200 utilisations de véhicules ; l'application conserve tout l'historique au fil des lectures.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    choosing?.let { v ->
        CompanyDialog(v, companies, vehicleCompany[v.key], onDismiss = { choosing = null }) { onAssign(v.key, it); choosing = null }
    }
}

@Composable
private fun CompanyDialog(v: Vehicle, companies: List<String>, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Société — ${v.registration}") },
        text = {
            Column {
                companies.forEach { c ->
                    Text(
                        (if (c == current) "✓ " else "") + c,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                    )
                    Divider()
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nouvelle société") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onPick(name.trim()) }, enabled = name.isNotBlank()) { Text("Ajouter") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onPick(null) }) { Text("Retirer") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}
