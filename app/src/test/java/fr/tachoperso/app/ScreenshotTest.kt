package fr.tachoperso.app

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
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { DashboardTab(p, h, {}, {}) } } }
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
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { DayDetail(h, date) } } }
        shot("5-detail-jour-positions")
    }

    @Test
    fun ferry() {
        val (_, h) = history()
        compose.setContent { TachoTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { fr.tachoperso.app.ui.FerryScreen(h) {} } } }
        shot("6-ferry")
    }
}
