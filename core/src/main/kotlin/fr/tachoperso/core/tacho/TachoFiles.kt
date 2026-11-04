package fr.tachoperso.core.tacho

import fr.tachoperso.core.util.hex

/**
 * Applications tachygraphe d'une carte conducteur (Règlement (UE) 2016/799, Annexe 1C, Appendice 2).
 * Une carte Gen2 contient les deux applications : TACHO (Gen1, rétrocompatibilité) et SMRDT (Gen2).
 */
enum class Generation(val label: String, val aid: ByteArray, val dataTag: Int, val signatureTag: Int) {
    GEN1("Gen1 (TACHO)", hex("FF544143484F"), 0x00, 0x01),
    GEN2("Gen2 (SMRDT)", hex("FF534D524454"), 0x02, 0x03),
}

/** Fichier élémentaire (EF). [signed] : inclus avec signature dans un téléchargement .DDD. */
data class EfDef(val fid: Int, val name: String, val signed: Boolean)

object TachoFiles {
    /** Fichiers de la racine (MF), communs aux deux générations, jamais signés. */
    val MF = listOf(
        EfDef(0x0002, "EF_ICC", false),
        EfDef(0x0005, "EF_IC", false),
    )

    val GEN1 = listOf(
        EfDef(0x0501, "Application_Identification", true),
        EfDef(0xC100, "Card_Certificate", false),
        EfDef(0xC108, "CA_Certificate", false),
        EfDef(0x0520, "Identification", true),
        EfDef(0x050E, "Card_Download", true),
        EfDef(0x0521, "Driving_Licence_Info", true),
        EfDef(0x0502, "Events_Data", true),
        EfDef(0x0503, "Faults_Data", true),
        EfDef(0x0504, "Driver_Activity_Data", true),
        EfDef(0x0505, "Vehicles_Used", true),
        EfDef(0x0506, "Places", true),
        EfDef(0x0507, "Current_Usage", true),
        EfDef(0x0508, "Control_Activity_Data", true),
        EfDef(0x0522, "Specific_Conditions", true),
    )

    val GEN2 = listOf(
        EfDef(0x0501, "Application_Identification", true),
        EfDef(0xC101, "CardSignCertificate", false),
        EfDef(0xC108, "CA_Certificate", false),
        EfDef(0xC109, "Link_Certificate", false),
        EfDef(0x0520, "Identification", true),
        EfDef(0x050E, "Card_Download", true),
        EfDef(0x0521, "Driving_Licence_Info", true),
        EfDef(0x0502, "Events_Data", true),
        EfDef(0x0503, "Faults_Data", true),
        EfDef(0x0504, "Driver_Activity_Data", true),
        EfDef(0x0505, "Vehicles_Used", true),
        EfDef(0x0506, "Places", true),
        EfDef(0x0507, "Current_Usage", true),
        EfDef(0x0508, "Control_Activity_Data", true),
        EfDef(0x0522, "Specific_Conditions", true),
        EfDef(0x0523, "VehicleUnits_Used", true),
        EfDef(0x0524, "GNSS_Places", true),
        // Fichiers Gen2 version 2 (cartes « smart V2 », absents d'une Gen2 V1).
        EfDef(0x0525, "Application_Identification_V2", true),
        EfDef(0x0526, "Places_Authentication", true),
        EfDef(0x0527, "GNSS_Places_Authentication", true),
        EfDef(0x0528, "Border_Crossings", true),
        EfDef(0x0529, "Load_Unload_Operations", true),
        EfDef(0x0530, "Load_Type_Entries", true),
        EfDef(0x0540, "VU_Configuration", true),
    )

    fun of(generation: Generation?): List<EfDef> = when (generation) {
        null -> MF
        Generation.GEN1 -> GEN1
        Generation.GEN2 -> GEN2
    }

    fun name(generation: Generation?, fid: Int): String =
        of(generation).firstOrNull { it.fid == fid }?.name ?: "EF_%04X".format(fid)
}

/** Contenu d'un fichier lu sur la carte. [generation] null = fichier de la racine (MF). */
class CardFile(
    val generation: Generation?,
    val fid: Int,
    val data: ByteArray,
    val signature: ByteArray? = null,
) {
    val name: String get() = TachoFiles.name(generation, fid)
}

/** Ensemble des fichiers lus sur une carte (ou importés d'un .DDD). */
class CardDump(
    val files: List<CardFile>,
    val atr: ByteArray? = null,
    val warnings: List<String> = emptyList(),
) {
    fun file(generation: Generation?, fid: Int): CardFile? =
        files.firstOrNull { it.generation == generation && it.fid == fid }

    fun has(generation: Generation): Boolean = files.any { it.generation == generation }

    /** Génération la plus récente présente : c'est elle qu'on analyse en priorité. */
    val bestGeneration: Generation?
        get() = if (has(Generation.GEN2)) Generation.GEN2 else if (has(Generation.GEN1)) Generation.GEN1 else null
}
