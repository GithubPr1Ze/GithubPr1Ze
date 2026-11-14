package fr.tachoperso.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.tachoperso.app.LocalPlaceNames
import fr.tachoperso.app.placeName
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.Night
import fr.tachoperso.core.rules.Nights
import fr.tachoperso.core.rules.Stop
import fr.tachoperso.core.rules.Stops
import fr.tachoperso.core.rules.hm
import fr.tachoperso.core.tacho.GeoPoint
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val HomeColor = Color(0xFF3E9A6B)

private enum class Span(val label: String) { MONTH("Mois"), YEAR("Année"), ALL("Tout") }

private val dayNum: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd/MM/yy", Locale.FRENCH)

/**
 * Onglet « Lieux » : où le conducteur coupe. Carte et classement des lieux de coupure connus à
 * 2 km près, et historique de toutes les coupures (repos de 9 h et plus).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesTab(
    history: DriverHistory,
    onOpenDay: (LocalDate) -> Unit,
    map: @Composable (stops: List<Stop>, home: GeoPoint?, onSelect: (Stop) -> Unit, modifier: Modifier) -> Unit =
        { s, h, o, m -> StopsMap(s, h, o, m) },
) {
    val nights = history.nights
    if (nights.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Panel(title = "Lieux de coupure") {
                Text("Aucun repos de 9 h ou plus dans les lectures enregistrées.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val first = nights.minOf { it.date }
    val last = nights.maxOf { it.date }
    var span by rememberSaveable { mutableStateOf(Span.ALL) }
    // Décalage en mois (ou en années) par rapport à la coupure la plus récente.
    var back by rememberSaveable { mutableStateOf(0) }
    var ranking by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Stop?>(null) }

    val (from, to, label) = when (span) {
        Span.MONTH -> YearMonth.from(last).minusMonths(back.toLong()).let {
            Triple(it.atDay(1), it.atEndOfMonth(), monthFmt.format(it).capitalized())
        }
        Span.YEAR -> (last.year - back).let { Triple(LocalDate.of(it, 1, 1), LocalDate.of(it, 12, 31), it.toString()) }
        Span.ALL -> Triple(first, last, "")
    }
    val stops = remember(history, from, to) { Stops.within(history.stops, from, to) }
    val shown = remember(history, from, to) {
        nights.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedByDescending { it.rest.start }
    }
    val located = stops.sumOf { it.count }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Span.entries.forEach { s ->
                FilterChip(selected = span == s, onClick = { span = s; back = 0 }, label = { Text(s.label) })
            }
            Spacer(Modifier.weight(1f))
            if (span != Span.ALL) {
                IconButton(onClick = { back++ }, enabled = from.isAfter(first)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Période précédente")
                }
                Text(label, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                IconButton(onClick = { back-- }, enabled = back > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Période suivante")
                }
            }
        }

        map(stops, history.home, { selected = it }, Modifier.fillMaxWidth().weight(0.42f))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = ranking, onClick = { ranking = true }, label = { Text("Classement") })
            FilterChip(selected = !ranking, onClick = { ranking = false }, label = { Text("Historique") })
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(0.58f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "$located coupure(s) sur ${shown.size} localisée(s) à 2 km près. La carte n'enregistre qu'un point " +
                        "toutes les 3 h de conduite : les autres coupures figurent dans l'historique, avec leur marge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ranking) {
                items(stops, key = { "s" + it.anchor.label() }) { s ->
                    StopRow(s, history.home) { selected = s }
                }
            } else {
                items(shown, key = { "n" + it.rest.start.epochSecond }) { n ->
                    NightRow(n, onOpenDay)
                }
            }
        }
    }

    selected?.let { s ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            StopSheet(s, history.home) { selected = null; onOpenDay(it) }
        }
    }
}

private fun isHome(s: Stop, home: GeoPoint?) = home?.let { Nights.distanceKm(it, s.center) <= Nights.RADIUS_KM } ?: false

private fun Stop.summary(): String = listOfNotNull(
    "$count coupure(s)",
    if (weekly > 0) "dont $weekly repos hebdo" else null,
    "dernière fois le ${dateFmt.format(last.date)}",
).joinToString("  ·  ")

@Composable
private fun StopRow(s: Stop, home: GeoPoint?, onClick: () -> Unit) {
    val name = placeName(s.anchor, LocalPlaceNames.current)
    Panel(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name ?: s.center.label(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (isHome(s, home)) TagBadge("Domicile", HomeColor)
        }
        Text(s.summary(), style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NightRow(n: Night, onOpenDay: (LocalDate) -> Unit) {
    val uri = LocalUriHandler.current
    val point = n.point
    val name = placeName(point, LocalPlaceNames.current)
    val exact = Stops.isExact(n)
    val place = when {
        point == null -> "Lieu inconnu"
        exact -> name ?: point.label()
        else -> "Vers ${name ?: point.label()}, à ${n.uncertaintyKm?.toInt()} km près"
    }
    Panel(modifier = Modifier.clickable { onOpenDay(n.date) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(dayNum.format(n.date).capitalized(), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp))
            Text(
                "${n.rest.start.localTime()} → ${n.rest.end.localTime()}  ·  ${hm(n.rest.minutes)}",
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                place + (if (n.weekly) "  ·  repos hebdo" else "") + if (n.onFerry) "  ·  à bord d'un ferry/train" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (exact) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (point != null && exact) {
                Text(
                    "Carte",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openMap(uri::openUri, point, "Coupure du ${dateFmt.format(n.date)}") }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** Fiche d'un lieu : nom, fréquentation, lien vers l'application de cartes, dates des coupures. */
@Composable
private fun StopSheet(s: Stop, home: GeoPoint?, onOpenDay: (LocalDate) -> Unit) {
    val uri = LocalUriHandler.current
    val name = placeName(s.anchor, LocalPlaceNames.current)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name ?: s.center.label(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (isHome(s, home)) TagBadge("Domicile", HomeColor)
        }
        Text(
            s.center.label() + "  ·  " + s.summary(),
            style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { openMap(uri::openUri, s.center, name ?: "Lieu de coupure") }, modifier = Modifier.fillMaxWidth()) {
            Text("Ouvrir dans Google Maps")
        }
        SectionLabel("Coupures en ce lieu")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(s.nights.sortedByDescending { it.rest.start }, key = { it.rest.start.epochSecond }) { n ->
                Row(Modifier.fillMaxWidth().clickable { onOpenDay(n.date) }.padding(vertical = 8.dp)) {
                    Text(dayNum.format(n.date).capitalized(), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), modifier = Modifier.weight(1f))
                    Text(
                        "${n.rest.start.localTime()} → ${n.rest.end.localTime()}  ·  ${hm(n.rest.minutes)}",
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
