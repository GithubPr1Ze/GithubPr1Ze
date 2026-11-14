package fr.tachoperso.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.tachoperso.core.rules.Nights
import fr.tachoperso.core.rules.Stop
import fr.tachoperso.core.tacho.GeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.hypot

/**
 * Carte OpenStreetMap (osmdroid, sans clé ni compte) des lieux de coupure : une pastille par lieu,
 * avec le nombre de coupures. Les tuiles déjà vues restent en cache sur le téléphone.
 */
@Composable
fun StopsMap(stops: List<Stop>, home: GeoPoint?, onSelect: (Stop) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val select = rememberUpdatedState(onSelect)
    val colors = MaterialTheme.colorScheme
    val overlay = remember {
        StopsOverlay(context.resources.displayMetrics.density) { select.value(it) }
    }
    overlay.fill = colors.primary.toArgb()
    overlay.text = colors.onPrimary.toArgb()
    overlay.homeFill = HomeColor.toArgb()
    overlay.ring = colors.surface.toArgb()

    val map = remember {
        Configuration.getInstance().apply {
            // Exigé par les serveurs de tuiles OpenStreetMap : un identifiant propre à l'application.
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 18.0
            isVerticalMapRepetitionEnabled = false
            overlays.add(overlay)
            controller.setZoom(5.0)
            controller.setCenter(org.osmdroid.util.GeoPoint(49.5, 2.5))
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, map) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume() else if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }

    // La vue Android dessine hors de son cadre si on ne la rogne pas.
    Box(modifier.clipToBounds()) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) NightTiles else null)
                overlay.home = home
                if (overlay.stops != stops) {
                    overlay.stops = stops
                    frame(m, stops)
                }
                m.invalidate()
            },
        )
        Text(
            "© OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).background(colors.surface.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
        )
    }
}

/**
 * Thème sombre : clair et foncé inversés, teintes conservées (la mer reste bleue, la terre sombre).
 * Inversion des couleurs suivie d'une rotation de teinte de 180°.
 */
private val NightTiles = android.graphics.ColorMatrixColorFilter(
    floatArrayOf(
        1 / 3f, -2 / 3f, -2 / 3f, 0f, 255f,
        -2 / 3f, 1 / 3f, -2 / 3f, 0f, 255f,
        -2 / 3f, -2 / 3f, 1 / 3f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/** Cadre la carte sur l'ensemble des lieux (ou sur le lieu unique). */
private fun frame(map: MapView, stops: List<Stop>) {
    if (stops.isEmpty()) return
    val apply = {
        val points = stops.map { org.osmdroid.util.GeoPoint(it.center.latitude, it.center.longitude) }
        val box = BoundingBox.fromGeoPoints(points)
        if (points.size == 1 || (box.latitudeSpan < 0.02 && box.longitudeSpanWithDateLine < 0.02)) {
            map.controller.setZoom(11.0)
            map.controller.setCenter(points.first())
        } else {
            map.zoomToBoundingBox(box.increaseByScale(1.4f), false)
        }
    }
    if (map.width > 0 && map.height > 0) apply() else map.addOnFirstLayoutListener { _, _, _, _, _ -> apply() }
}

/** Pastilles numérotées ; un appui sélectionne le lieu le plus proche du doigt. */
private class StopsOverlay(private val density: Float, private val onSelect: (Stop) -> Unit) : Overlay() {
    var stops: List<Stop> = emptyList()
    var home: GeoPoint? = null
    var fill = 0
    var homeFill = 0
    var text = 0
    var ring = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val px = Point()

    private fun radius(s: Stop) = (11f + 1.5f * minOf(s.count, 8)) * density
    private fun isHome(s: Stop) = home?.let { Nights.distanceKm(it, s.center) <= Nights.RADIUS_KM } ?: false
    private fun locate(map: MapView, s: Stop): Point =
        map.projection.toPixels(org.osmdroid.util.GeoPoint(s.center.latitude, s.center.longitude), px)

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        // Les lieux les plus fréquentés sont dessinés en dernier, donc au-dessus.
        for (s in stops.asReversed()) {
            val p = locate(map, s)
            val r = radius(s)
            paint.style = Paint.Style.FILL
            paint.color = ring
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r + 2f * density, paint)
            paint.color = if (isHome(s)) homeFill else fill
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, paint)
            label.color = text
            label.textSize = 12f * density
            canvas.drawText(s.count.toString(), p.x.toFloat(), p.y - (label.ascent() + label.descent()) / 2, label)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val hit = stops
            .map { s -> locate(map, s).let { s to hypot(e.x - it.x, e.y - it.y) } }
            .filter { (s, d) -> d <= radius(s) + 10f * density }
            .minByOrNull { it.second }?.first ?: return false
        onSelect(hit)
        return true
    }
}
