package fr.tachoperso.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.tachoperso.core.rules.Finding
import fr.tachoperso.core.rules.Period
import fr.tachoperso.core.rules.Severity
import fr.tachoperso.core.rules.hm
import fr.tachoperso.core.tacho.ActivityType
import java.time.Duration
import java.time.LocalDate

/** Bloc de contenu à bordure fine, titre en petites capitales. */
@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || trailing != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) SectionLabel(title, Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color? = null) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
    }
}

/** Jauge valeur / limite avec libellé. */
@Composable
fun Gauge(label: String, value: Long, limit: Long, note: String? = null) {
    val ratio = if (limit > 0) value.toFloat() / limit else 0f
    val color = when {
        ratio > 1f -> severityColor(Severity.INFRACTION)
        ratio >= 0.9f -> severityColor(Severity.WARNING)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${hm(value)} / ${hm(limit)}",
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                fontWeight = FontWeight.SemiBold,
                color = if (ratio > 1f) color else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Frise des activités d'une journée locale (gère les jours de 23 h et 25 h). */
@Composable
fun DayBar(date: LocalDate, periods: List<Period>, height: Int = 14, showScale: Boolean = true) {
    val dayStart = date.atStartOfDay(ZONE).toInstant()
    val total = Duration.between(dayStart, date.plusDays(1).atStartOfDay(ZONE).toInstant()).toMinutes().toFloat()
    Column {
        Row(
            Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            var cursor = 0L
            for (p in periods) {
                val s = Duration.between(dayStart, p.start).toMinutes()
                val e = Duration.between(dayStart, p.end).toMinutes()
                if (s > cursor) Spacer(Modifier.weight((s - cursor).toFloat()))
                if (e > s) {
                    val color = if (p.cardOut && p.type == ActivityType.REST) CardOutColor else activityColor(p.type)
                    Box(Modifier.weight((e - s).toFloat()).fillMaxHeight().background(color))
                }
                cursor = maxOf(cursor, e)
            }
            if (total > cursor) Spacer(Modifier.weight(total - cursor))
        }
        if (showScale) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0h", "6h", "12h", "18h", "24h").forEach {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            "Conduite" to activityColor(ActivityType.DRIVING),
            "Travail" to activityColor(ActivityType.WORK),
            "Dispo." to activityColor(ActivityType.AVAILABILITY),
            "Repos" to activityColor(ActivityType.REST),
            "Carte retirée" to CardOutColor,
        ).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, RoundedCornerShape(1.dp)))
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SeverityBadge(severity: Severity, text: String = severity.label) = TagBadge(text, severityColor(severity))

/** Étiquette encadrée, couleur libre. */
@Composable
fun TagBadge(text: String, color: Color) {
    Box(
        Modifier.border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
fun FindingRow(f: Finding, showDate: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).background(severityColor(f.severity), RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(f.rule.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (showDate) {
                    Text(
                        dayShort.format(f.at.atZone(ZONE)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Constaté : ${f.observed}  ·  Limite : ${f.limit}",
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
            )
            if (f.detail.isNotBlank()) {
                Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(f.rule.article, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

const val DISCLAIMER =
    "Outil d'aide personnel, sans valeur légale. Les calculs suivent le règlement (CE) 561/2006 et le " +
        "Code des transports ; seules les données de la carte sont prises en compte."
