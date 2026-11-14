package fr.tachoperso.app

import fr.tachoperso.core.rules.DriverHistory
import fr.tachoperso.core.rules.NightStatus
import fr.tachoperso.core.tacho.CardDump
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.DddFormat
import fr.tachoperso.core.tacho.GeoPoint
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class DriverProfile(
    val key: String,
    val surname: String,
    val firstNames: String,
    val cardNumber: String,
    val expiry: Instant?,
    val email: String,
    val lastRead: Instant?,
) {
    val displayName: String get() = listOf(surname, firstNames).filter { it.isNotBlank() }.joinToString(" ").ifBlank { cardNumber }
}

/**
 * Stockage local : un dossier par conducteur (clé = pays + 14 premiers caractères du n° de carte,
 * stables lors d'un renouvellement), contenant profile.json et toutes les lectures .DDD.
 */
class DriverRepository(root: File) {
    private val driversDir = File(root, "drivers").apply { mkdirs() }

    fun drivers(): List<DriverProfile> =
        driversDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { loadProfile(it.name) }
            ?.sortedByDescending { it.lastRead } ?: emptyList()

    fun profile(key: String): DriverProfile? = loadProfile(key)

    /** Enregistre une lecture (carte ou import) et met à jour la fiche. Renvoie la fiche et le nom du fichier. */
    fun save(dump: CardDump): Pair<DriverProfile, String> {
        val report = CardReport(dump)
        val id = report.identity ?: throw IllegalStateException("Identité du conducteur illisible : lecture non enregistrée")
        val key = keyOf(id.issuingNation, id.cardNumber)
        val dir = File(driversDir, key)
        val reads = File(dir, "reads").apply { mkdirs() }
        val name = report.suggestedFileName()
        File(reads, name).writeBytes(DddFormat.write(dump))
        val previous = loadProfile(key)
        val profile = DriverProfile(
            key = key,
            surname = id.surname,
            firstNames = id.firstNames,
            cardNumber = id.cardNumber,
            expiry = id.expiryDate,
            email = previous?.email ?: "",
            lastRead = Instant.now(),
        )
        writeProfile(profile)
        return profile to name
    }

    fun setEmail(key: String, email: String) {
        loadProfile(key)?.let { writeProfile(it.copy(email = email.trim())) }
    }

    fun reads(key: String): List<File> =
        File(driversDir, "$key/reads").listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()

    fun history(key: String): DriverHistory {
        val reports = reads(key).mapNotNull { f -> runCatching { CardReport(DddFormat.read(f.readBytes())) }.getOrNull() }
        val settings = settings(key)
        return DriverHistory(
            reports,
            until = loadProfile(key)?.lastRead ?: Instant.now(),
            declaredHome = settings.home,
            nightOverrides = settings.nights,
        )
    }

    /** Réglages du conducteur : domicile déclaré et corrections manuelles des nuits. */
    data class Settings(val home: GeoPoint?, val nights: Map<Instant, NightStatus>)

    fun settings(key: String): Settings {
        val f = File(driversDir, "$key/settings.json")
        if (!f.isFile) return Settings(null, emptyMap())
        return runCatching {
            val j = JSONObject(f.readText())
            val home = j.optJSONObject("home")?.let { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }
            val n = j.optJSONObject("nights")
            val nights = n?.keys()?.asSequence()?.mapNotNull { k ->
                runCatching { Instant.ofEpochSecond(k.toLong()) to NightStatus.valueOf(n.getString(k)) }.getOrNull()
            }?.toMap() ?: emptyMap()
            Settings(home, nights)
        }.getOrElse { Settings(null, emptyMap()) }
    }

    fun setHome(key: String, home: GeoPoint?) = writeSettings(key, settings(key).copy(home = home))

    fun setNight(key: String, restStart: Instant, status: NightStatus?) {
        val s = settings(key)
        val nights = s.nights.toMutableMap()
        if (status == null) nights.remove(restStart) else nights[restStart] = status
        writeSettings(key, s.copy(nights = nights))
    }

    private fun writeSettings(key: String, s: Settings) {
        val j = JSONObject()
        s.home?.let { j.put("home", JSONObject().put("lat", it.latitude).put("lon", it.longitude)) }
        val n = JSONObject()
        s.nights.forEach { (k, v) -> n.put(k.epochSecond.toString(), v.name) }
        j.put("nights", n)
        val dir = File(driversDir, key).apply { mkdirs() }
        val tmp = File(dir, "settings.json.tmp")
        tmp.writeText(j.toString(2))
        tmp.renameTo(File(dir, "settings.json"))
    }

    private fun loadProfile(key: String): DriverProfile? {
        val f = File(driversDir, "$key/profile.json")
        if (!f.isFile) return null
        return runCatching {
            val j = JSONObject(f.readText())
            DriverProfile(
                key = key,
                surname = j.optString("surname"),
                firstNames = j.optString("firstNames"),
                cardNumber = j.optString("cardNumber"),
                expiry = j.optLong("expiry", 0).takeIf { it > 0 }?.let(Instant::ofEpochSecond),
                email = j.optString("email"),
                lastRead = j.optLong("lastRead", 0).takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            )
        }.getOrNull()
    }

    private fun writeProfile(p: DriverProfile) {
        val j = JSONObject()
            .put("surname", p.surname)
            .put("firstNames", p.firstNames)
            .put("cardNumber", p.cardNumber)
            .put("expiry", p.expiry?.epochSecond ?: 0)
            .put("email", p.email)
            .put("lastRead", p.lastRead?.epochSecond ?: 0)
        val dir = File(driversDir, p.key).apply { mkdirs() }
        val tmp = File(dir, "profile.json.tmp")
        tmp.writeText(j.toString(2))
        tmp.renameTo(File(dir, "profile.json"))
    }

    companion object {
        fun keyOf(nation: String, cardNumber: String): String =
            (nation + "_" + cardNumber.take(14)).map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }
}
