package fr.tachoperso.core.tacho

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Vue « lisible » d'un CardDump : ce que l'application affiche. */
class CardReport(val dump: CardDump) {
    val generation: Generation? = dump.bestGeneration

    private fun data(fid: Int): ByteArray? = dump.file(generation, fid)?.data

    val identity: CardIdentity? = data(0x0520)?.let(CardIdentity::parse)
    val licence: DrivingLicence? = data(0x0521)?.let(DrivingLicence::parse)
    val application: ApplicationIdentification? =
        generation?.let { g -> data(0x0501)?.let { ApplicationIdentification.parse(it, g) } }
    val lastCardDownload: Instant? = data(0x050E)?.let { DataTypes.timeReal(it, 0) }
    val currentUsage: CurrentUsage? = data(0x0507)?.let(CurrentUsage::parse)
    val days: List<DailyActivity> = data(0x0504)?.let(DriverActivityParser::parse) ?: emptyList()

    val generationLabel: String
        get() = when {
            generation == null -> "Inconnue"
            generation == Generation.GEN1 -> "Génération 1"
            dump.file(Generation.GEN2, 0x0525) != null -> "Génération 2 version 2 (Smart V2)"
            else -> "Génération 2 version 1 (Smart V1)"
        }

    val signedFiles: Int get() = dump.files.count { it.signature != null }

    /** Nom de fichier conventionnel : C_AAAAMMJJ_HHMM_I_NOM_NUMERO.DDD */
    fun suggestedFileName(at: Instant = Instant.now()): String {
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneOffset.UTC).format(at)
        val initial = identity?.firstNames?.firstOrNull()?.toString() ?: "X"
        val name = identity?.surname?.ifBlank { null } ?: "INCONNU"
        val number = identity?.cardNumber?.ifBlank { null } ?: "0"
        val raw = "C_${ts}_${initial}_${name}_$number"
        return raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("") + ".DDD"
    }
}
