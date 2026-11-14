package fr.tachoperso.app

import android.content.Context
import fr.tachoperso.core.rules.Rates
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate

/**
 * Barème des frais de déplacement : copie intégrée à l'application, remplacée par la dernière
 * version publiée sur GitHub (vérifiée à chaque démarrage, gardée pour fonctionner hors réseau).
 */
class BaremeRepository(private val context: Context) {
    private val cache = File(context.filesDir, "bareme.json")

    data class Bareme(val rates: List<Rates>, val updated: String, val origin: String)

    /** Barème disponible tout de suite : téléchargé précédemment, sinon celui de l'application. */
    fun local(): Bareme {
        runCatching { if (cache.isFile) return parse(cache.readText(), "téléchargé le ${java.text.DateFormat.getDateInstance().format(cache.lastModified())}") }
        return parse(context.assets.open("bareme.json").bufferedReader().readText(), "intégré à l'application")
    }

    /** Télécharge la version publiée ; renvoie null sans réseau ou si le fichier est invalide. */
    fun refresh(): Bareme? = runCatching {
        val c = URL(URL_BAREME).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("Cache-Control", "no-cache")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val b = parse(text, "téléchargé le ${java.text.DateFormat.getDateInstance().format(Instant.now().toEpochMilli())}")
        require(b.rates.isNotEmpty())
        val tmp = File(context.filesDir, "bareme.json.tmp")
        tmp.writeText(text)
        tmp.renameTo(cache)
        b
    }.getOrNull()

    companion object {
        const val URL_BAREME = "https://raw.githubusercontent.com/GithubPr1Ze/tacho-perso-bareme/main/bareme.json"

        fun parse(text: String, origin: String): Bareme {
            val j = JSONObject(text)
            val arr = j.getJSONArray("baremes")
            // Seuls les barèmes signés (« en_vigueur ») comptent ; une proposition n'est jamais appliquée.
            val rates = (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
                it.optString("statut", "en_vigueur") == "en_vigueur"
            }.map { b ->
                val t = b.getJSONObject("taux")
                val src = b.optJSONObject("source")
                Rates(
                    effective = LocalDate.parse(b.getString("dateEffet")),
                    source = src?.optString("texte") ?: "",
                    repas = t.getDouble("repas"),
                    repasUnique = t.getDouble("repasUnique"),
                    repasUniqueNuit = t.getDouble("repasUniqueNuit"),
                    indemniteSpeciale = t.getDouble("indemniteSpeciale"),
                    casseCroute = t.getDouble("casseCroute"),
                    grandDeplacement1Repas = t.getDouble("grandDeplacement1Repas"),
                    grandDeplacement2Repas = t.getDouble("grandDeplacement2Repas"),
                )
            }
            return Bareme(rates.sortedBy { it.effective }, j.optString("misAJour"), origin)
        }

    }
}
