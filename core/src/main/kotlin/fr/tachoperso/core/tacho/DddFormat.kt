package fr.tachoperso.core.tacho

import fr.tachoperso.core.util.u16be
import fr.tachoperso.core.util.u8
import java.io.ByteArrayOutputStream

/**
 * Format de fichier de téléchargement carte (.DDD / .C1B / .TGD), Annexe 1C Appendice 7 :
 * suite d'éléments  FID (2 octets) | type (1 octet) | longueur (2 octets) | valeur.
 * Type : 00 = données Gen1 (ou racine), 01 = signature Gen1, 02 = données Gen2, 03 = signature Gen2.
 */
object DddFormat {
    fun write(dump: CardDump): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in dump.files) {
            val dataTag = f.generation?.dataTag ?: 0x00
            writeTlv(out, f.fid, dataTag, f.data)
            if (f.signature != null && f.generation != null) {
                writeTlv(out, f.fid, f.generation.signatureTag, f.signature)
            }
        }
        return out.toByteArray()
    }

    private fun writeTlv(out: ByteArrayOutputStream, fid: Int, type: Int, value: ByteArray) {
        require(value.size <= 0xFFFF)
        out.write(fid shr 8); out.write(fid and 0xFF)
        out.write(type)
        out.write(value.size shr 8); out.write(value.size and 0xFF)
        out.write(value)
    }

    fun read(bytes: ByteArray): CardDump {
        val files = mutableListOf<CardFile>()
        val warnings = mutableListOf<String>()
        var i = 0
        while (i + 5 <= bytes.size) {
            val fid = bytes.u16be(i)
            val type = bytes.u8(i + 2)
            val len = bytes.u16be(i + 3)
            if (i + 5 + len > bytes.size) {
                warnings += "Fichier .DDD tronqué à l'octet $i"
                break
            }
            val value = bytes.copyOfRange(i + 5, i + 5 + len)
            i += 5 + len
            when (type) {
                0x00 -> {
                    val gen = if (fid == 0x0002 || fid == 0x0005) null else Generation.GEN1
                    files += CardFile(gen, fid, value)
                }
                0x02 -> files += CardFile(Generation.GEN2, fid, value)
                0x01, 0x03 -> {
                    val gen = if (type == 0x01) Generation.GEN1 else Generation.GEN2
                    val idx = files.indexOfLast { it.generation == gen && it.fid == fid }
                    if (idx >= 0) {
                        val f = files[idx]
                        files[idx] = CardFile(f.generation, f.fid, f.data, value)
                    } else {
                        warnings += "Signature orpheline pour %04X".format(fid)
                    }
                }
                else -> {
                    warnings += "Élément de type %02X inconnu à l'octet %d : lecture arrêtée".format(type, i - 5 - len)
                    break
                }
            }
        }
        if (files.isEmpty()) warnings += "Aucun fichier carte reconnu (est-ce bien un téléchargement de carte conducteur ?)"
        return CardDump(files, warnings = warnings)
    }
}
