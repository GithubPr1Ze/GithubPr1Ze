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
 * Réglages de paye du conducteur, saisis d'après ses bulletins et gardés sur le téléphone :
 * forfait d'heures payées, heures de nuit payées, majoration des frais à l'étranger.
 */
data class PaySettings(
    val paidHours: Double = 220.0,
    val paidNightHours: Double = 40.0,
    val markupPercent: Double = 18.0,
)

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
    data class Settings(
        val home: GeoPoint?,
        val nights: Map<Instant, NightStatus>,
        val abroadMarkup: Boolean = false,
        val pay: PaySettings = PaySettings(),
        val payslips: Map<java.time.YearMonth, fr.tachoperso.core.rules.PayslipCounts> = emptyMap(),
        /** Corrections manuelles des repas / découchers, par jour (mode employeur). */
        val days: Map<java.time.LocalDate, fr.tachoperso.core.rules.DayOverride> = emptyMap(),
    )

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
            val pay = j.optJSONObject("pay")?.let { o ->
                PaySettings(
                    paidHours = o.optDouble("paidHours", 220.0),
                    paidNightHours = o.optDouble("paidNightHours", 40.0),
                    markupPercent = o.optDouble("markupPercent", 18.0),
                )
            } ?: PaySettings()
            val ps = j.optJSONObject("payslips")
            val payslips = ps?.keys()?.asSequence()?.mapNotNull { k ->
                runCatching {
                    val o = ps.getJSONObject(k)
                    java.time.YearMonth.parse(k) to fr.tachoperso.core.rules.PayslipCounts(o.getInt("dej"), o.getInt("din"), o.getInt("dec"), o.getInt("pdj"))
                }.getOrNull()
            }?.toMap() ?: emptyMap()
            val dj = j.optJSONObject("days")
            fun b(o: JSONObject, k: String): Boolean? = if (o.has(k)) o.getBoolean(k) else null
            val days = dj?.keys()?.asSequence()?.mapNotNull { k ->
                runCatching {
                    val o = dj.getJSONObject(k)
                    java.time.LocalDate.parse(k) to fr.tachoperso.core.rules.DayOverride(b(o, "dej"), b(o, "din"), b(o, "pdj"), b(o, "dec"))
                }.getOrNull()
            }?.toMap() ?: emptyMap()
            Settings(home, nights, j.optBoolean("abroadMarkup", false), pay, payslips, days)
        }.getOrElse { Settings(null, emptyMap()) }
    }

    fun setHome(key: String, home: GeoPoint?) = writeSettings(key, settings(key).copy(home = home))

    fun setAbroadMarkup(key: String, on: Boolean) = writeSettings(key, settings(key).copy(abroadMarkup = on))

    fun setPay(key: String, pay: PaySettings) = writeSettings(key, settings(key).copy(pay = pay))

    fun setDay(key: String, date: java.time.LocalDate, o: fr.tachoperso.core.rules.DayOverride?) {
        val s = settings(key)
        val m = s.days.toMutableMap()
        if (o == null || o.isEmpty) m.remove(date) else m[date] = o
        writeSettings(key, s.copy(days = m))
    }

    fun setPayslip(key: String, month: java.time.YearMonth, counts: fr.tachoperso.core.rules.PayslipCounts?) {
        val s = settings(key)
        val m = s.payslips.toMutableMap()
        if (counts == null) m.remove(month) else m[month] = counts
        writeSettings(key, s.copy(payslips = m))
    }

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
        j.put("abroadMarkup", s.abroadMarkup)
        j.put(
            "pay",
            JSONObject()
                .put("paidHours", s.pay.paidHours)
                .put("paidNightHours", s.pay.paidNightHours)
                .put("markupPercent", s.pay.markupPercent),
        )
        val ps = JSONObject()
        s.payslips.forEach { (m, c) ->
            ps.put(m.toString(), JSONObject().put("dej", c.dejeuners).put("din", c.diners).put("dec", c.decouchers).put("pdj", c.petitsDejeuners))
        }
        j.put("payslips", ps)
        val dj = JSONObject()
        s.days.forEach { (d, o) ->
            val x = JSONObject()
            o.dejeuner?.let { x.put("dej", it) }
            o.diner?.let { x.put("din", it) }
            o.petitDejeuner?.let { x.put("pdj", it) }
            o.decoucher?.let { x.put("dec", it) }
            dj.put(d.toString(), x)
        }
        j.put("days", dj)
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
