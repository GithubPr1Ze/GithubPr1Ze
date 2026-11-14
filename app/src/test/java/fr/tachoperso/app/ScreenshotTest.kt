package fr.tachoperso.app

import androidx.compose.foundation.background
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import fr.tachoperso.app.ui.CalendarTab
import fr.tachoperso.app.ui.DashboardTab
import fr.tachoperso.app.ui.DayDetail
import fr.tachoperso.app.ui.DaysTab
import fr.tachoperso.app.ui.TachoTheme
import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.DddFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Captures des écrans avec une vraie lecture (ignoré si samples/carte-reelle.DDD est absent). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1400dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun history(): Pair<DriverProfile, DriverHistory> {
        val file = File(System.getProperty("tacho.sample") ?: "")
        assumeTrue("Pas de fichier d'exemple", file.isFile)
        val report = CardReport(DddFormat.read(file.readBytes()))
        val id = report.identity!!
        val readAt = java.time.Instant.ofEpochMilli(file.lastModified())
        val profile = DriverProfile("k", id.surname, id.firstNames, id.cardNumber, id.expiryDate, "", readAt)
        return profile to DriverHistory(listOf(report), until = readAt)
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("${System.getProperty("tacho.shots")}/$name.png")

    @Test
    fun dashboard() {
        val (p, h) = history()
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { DashboardTab(p, h, {}, {}, expenses = h.expenses(rates, false)) } } }
        shot("1-tableau-de-bord")
    }

    @Test
    fun calendar() {
        val (_, h) = history()
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { CalendarTab(h) {} } } }
        shot("2-calendrier")
    }

    @Test
    fun days() {
        val (_, h) = history()
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { DaysTab(h) {} } } }
        shot("3-jours")
    }

    @Test
    fun dayDetail() {
        val (_, h) = history()
        val date = h.analysis.shifts.last { h.analysis.findingsOn(h.analysis.localDate(it.start)).isNotEmpty() }.start
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { DayDetail(h, h.analysis.localDate(date)) } } }
        shot("4-detail-jour")
    }

    @Test
    fun dayLocations() {
        val (_, h) = history()
        val date = h.localDays.map { it.date }.last { h.ferryOn(it).size >= 2 && h.locationsOn(it).size >= 2 }
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { DayDetail(h, date, expenses = h.expenses(rates, false)) } } }
        shot("5-detail-jour-positions")
    }

    @Test
    fun ferry() {
        val (_, h) = history()
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { fr.tachoperso.app.ui.FerryScreen(h) {} } } }
        shot("6-ferry")
    }

    @Test
    fun places() {
        val (_, h) = history()
        // La carte (tuiles en ligne) est remplacée par un fond neutre.
        val map: @androidx.compose.runtime.Composable (List<fr.tachoperso.core.rules.Stop>, fr.tachoperso.core.tacho.GeoPoint?, (fr.tachoperso.core.rules.Stop) -> Unit, androidx.compose.ui.Modifier) -> Unit =
            { _, _, _, m -> androidx.compose.foundation.layout.Box(m.background(androidx.compose.ui.graphics.Color(0xFFDDE3EA))) }
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { fr.tachoperso.app.ui.PlacesTab(h, {}, map) } } }
        shot("13-lieux")
    }

    private val rates = listOf(fr.tachoperso.core.rules.Rates(java.time.LocalDate.of(2026, 1, 1), "Avenant n° 81 du 2 décembre 2025", 16.36, 10.07, 9.81, 4.42, 8.87, 52.31, 68.67))

    @Test
    fun expenses() {
        val (_, h) = history()
        val b = fr.tachoperso.app.BaremeRepository.Bareme(rates, "2026-11-14", "intégré à l'application")
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { fr.tachoperso.app.ui.ExpensesScreen(h.expenses(rates, false), b, false, {}, {}) } } }
        shot("7-frais")
    }

    @Test
    fun pdfPages() {
        val (p, h) = history()
        val r = fr.tachoperso.core.rules.MonthlyReport.build(h, h.expenses(rates, false), java.time.YearMonth.of(2026, 10))
        val bitmaps = mutableListOf<android.graphics.Bitmap>()
        val pager = ReportPdf.Pager(
            newPage = { _ ->
                val b = android.graphics.Bitmap.createBitmap(ReportPdf.W * 2, ReportPdf.H * 2, android.graphics.Bitmap.Config.ARGB_8888)
                bitmaps += b
                android.graphics.Canvas(b).also { it.scale(2f, 2f) }
            },
            endPage = {},
        )
        ReportPdf.draw(pager, r, "CONDUCTEUR Exemple", "1000000000000003", "Avenant n° 81 (01/01/2026)")
        bitmaps.forEachIndexed { i, b ->
            java.io.File("${System.getProperty("tacho.shots")}/8-pdf-page${i + 1}.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        org.junit.Assert.assertTrue(bitmaps.size in 2..4)
    }

    @Test
    fun pay() {
        val (_, h) = history()
        val slips = mapOf(java.time.YearMonth.of(2026, 10) to fr.tachoperso.core.rules.PayslipCounts(20, 20, 17, 21))
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { fr.tachoperso.app.ui.PayScreen(h, rates, PaySettings(), slips, false, {}, { _, _ -> }) } } }
        shot("9-paye")
    }

    @Test
    fun clarify() {
        val (_, h) = history()
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { fr.tachoperso.app.ui.ClarifyScreen(h, { _, _ -> }, {}) } } }
        shot("10-a-preciser")
    }

    @Test
    fun monthEditor() {
        val (_, h) = history()
        val o = mapOf(java.time.LocalDate.of(2026, 10, 2) to fr.tachoperso.core.rules.DayOverride(diner = false))
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { fr.tachoperso.app.ui.MonthDaysEditor(h, rates, 18.0, o, java.time.YearMonth.of(2026, 10), { _, _ -> }, {}) } } }
        shot("11-editeur-mois")
    }

    @Test
    fun vehicles() {
        val (_, h) = history()
        // Sociétés fictives : aucune donnée réelle dans les tests.
        val vc = h.vehicles.mapIndexed { i, v -> v.key to (if (i < 3) "Société A" else "Société B") }.toMap()
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { fr.tachoperso.app.ui.VehiclesScreen(h, listOf("Société A", "Société B"), vc) { _, _ -> } } } }
        shot("12-vehicules")
    }
}
