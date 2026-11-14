package fr.tachoperso.app

import android.content.Context
import android.location.Geocoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import fr.tachoperso.core.tacho.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Noms de lieux (ville + pays) à partir des positions GNSS, via le service de géocodage de Google
 * intégré au téléphone. Chaque nom trouvé est gardé sur le téléphone : une seule recherche par lieu
 * (arrondi à ~100 m). Sans réseau, rien n'est mis en cache et l'appli affiche les coordonnées.
 */
class PlaceNames(context: Context) {
    private val file = File(context.filesDir, "place-names.json")
    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.FRANCE) else null
    private val mutex = Mutex()
    private val cache: MutableMap<String, String> = load()

    private fun key(p: GeoPoint) = "%.3f,%.3f".format(Locale.US, p.latitude, p.longitude)

    fun cached(p: GeoPoint): String? = synchronized(cache) { cache[key(p)] }

    suspend fun lookup(p: GeoPoint): String? {
        cached(p)?.let { return it }
        val g = geocoder ?: return null
        return mutex.withLock {
            cached(p) ?: withContext(Dispatchers.IO) {
                val name = try {
                    @Suppress("DEPRECATION")
                    g.getFromLocation(p.latitude, p.longitude, 1)?.firstOrNull()?.let { a ->
                        val place = a.locality ?: a.subLocality ?: a.subAdminArea ?: a.adminArea
                        place?.let { "$it (${a.countryCode ?: "?"})" }
                    }
                } catch (_: Exception) {
                    null
                }
                if (name != null) {
                    synchronized(cache) { cache[key(p)] = name }
                    save()
                }
                name
            }
        }
    }

    private fun load(): MutableMap<String, String> = try {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    } catch (_: Exception) {
        mutableMapOf()
    }

    private fun save() {
        val o = synchronized(cache) { JSONObject(cache as Map<*, *>) }
        try {
            file.writeText(o.toString())
        } catch (_: Exception) {
        }
    }
}

val LocalPlaceNames = staticCompositionLocalOf<PlaceNames?> { null }

/** Nom du lieu s'il est connu ou trouvable, sinon null (on affiche alors les coordonnées). */
@Composable
fun placeName(p: GeoPoint?, names: PlaceNames?): String? {
    if (p == null || names == null) return null
    return produceState(initialValue = names.cached(p), p) { if (value == null) value = names.lookup(p) }.value
}
