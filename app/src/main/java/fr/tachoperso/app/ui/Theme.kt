package fr.tachoperso.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.tachoperso.core.rules.Severity
import fr.tachoperso.core.tacho.ActivityType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val ZONE: ZoneId = ZoneId.of("Europe/Paris")

private val Light = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4EF),
    onPrimaryContainer = Color(0xFF0E2238),
    secondary = Color(0xFF4A5868),
    background = Color(0xFFF4F6F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6EAEE),
    onSurfaceVariant = Color(0xFF55606B),
    outline = Color(0xFFC5CCD3),
    outlineVariant = Color(0xFFDDE2E7),
    surfaceContainer = Color(0xFFF0F2F5),
    surfaceContainerLow = Color.White,
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB8DB),
    onPrimary = Color(0xFF0E2238),
    primaryContainer = Color(0xFF2B4466),
    onPrimaryContainer = Color(0xFFDCE4EF),
    secondary = Color(0xFFB4C0CC),
    background = Color(0xFF111418),
    surface = Color(0xFF181C21),
    surfaceVariant = Color(0xFF2A3038),
    onSurfaceVariant = Color(0xFFA9B3BD),
    outline = Color(0xFF48515B),
    outlineVariant = Color(0xFF2F363E),
    surfaceContainer = Color(0xFF1D2228),
    surfaceContainerLow = Color(0xFF181C21),
    error = Color(0xFFF2B8B5),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

@Composable
fun TachoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppTypography, content = content)
}

/** Couleurs des activités, sobres et distinctes en clair comme en sombre. */
fun activityColor(type: ActivityType): Color = when (type) {
    ActivityType.DRIVING -> Color(0xFFC8463D)
    ActivityType.WORK -> Color(0xFFD99A2B)
    ActivityType.AVAILABILITY -> Color(0xFF3C78B5)
    ActivityType.REST -> Color(0xFF3E9A6B)
}

val CardOutColor = Color(0xFF9AA3AC)

fun severityColor(s: Severity): Color = when (s) {
    Severity.OK -> Color(0xFF3E9A6B)
    Severity.WARNING -> Color(0xFFD08A1E)
    Severity.INFRACTION -> Color(0xFFC0392B)
}

fun activityLabel(type: ActivityType): String = when (type) {
    ActivityType.DRIVING -> "Conduite"
    ActivityType.WORK -> "Travail"
    ActivityType.AVAILABILITY -> "Disponibilité"
    ActivityType.REST -> "Repos"
}

val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

private val fr = Locale.FRENCH
val dayLong: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", fr)
val dayShort: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", fr)
val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", fr)
val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", fr)
val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", fr)
val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", fr)

fun String.capitalized(): String = replaceFirstChar { it.titlecase(fr) }
fun LocalDate.long(): String = dayLong.format(this).capitalized()
fun Instant.local(): String = dateTimeFmt.format(atZone(ZONE))
fun Instant.localDate(): String = dateFmt.format(atZone(ZONE))
fun Instant.localTime(): String = timeFmt.format(atZone(ZONE))
