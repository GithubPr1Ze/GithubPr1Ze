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
    // « -4 » : les noms de l'ancien cache pouvaient se limiter au comté (« Essex ») au lieu de la ville.
    private val file = File(context.filesDir, "place-names-4.json").also { _ ->
        listOf("place-names.json", "place-names-2.json", "place-names-3.json").forEach { File(context.filesDir, it).delete() }
    }
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
                    g.getFromLocation(p.latitude, p.longitude, 5)?.let(::bestName)
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

    /**
     * Le premier résultat est souvent une adresse sans ville (Royaume-Uni notamment) : on prend
     * la ville du premier résultat qui en donne une (champ dédié, sinon adresse), puis seulement le
     * quartier, le district, la région.
     */
    private fun bestName(results: List<android.location.Address>): String? {
        val levels = listOf<(android.location.Address) -> String?>({ it.locality }, ::townFromLine, { it.subLocality }, { it.subAdminArea }, { it.adminArea })
        for (level in levels) {
            val a = results.firstOrNull { !level(it).isNullOrBlank() } ?: continue
            val country = a.countryCode ?: results.firstNotNullOfOrNull { it.countryCode } ?: "?"
            return "${level(a)} ($country)"
        }
        return null
    }

    /**
     * Au Royaume-Uni le champ « ville » est vide, mais elle précède le code postal dans l'adresse
     * (« 1 High St, Witham CM8 3DX, Royaume-Uni » → « Witham »).
     */
    private fun townFromLine(a: android.location.Address): String? {
        val code = a.postalCode?.ifBlank { null } ?: return null
        val part = a.getAddressLine(0)?.split(",")?.map { it.trim() }?.firstOrNull { code in it } ?: return null
        return part.replace(code, "").trim().ifBlank { null }
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
