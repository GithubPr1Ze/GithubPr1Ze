package fr.tachoperso.core.tacho

import fr.tachoperso.core.util.u32be
import fr.tachoperso.core.util.u8
import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDate

/** Types de base du dictionnaire de données (Annexe 1C, Appendice 1). */
object DataTypes {
    /** TimeReal : secondes depuis le 01/01/1970 00:00 UTC. 0 et FFFFFFFF = non renseigné. */
    fun timeReal(b: ByteArray, off: Int): Instant? {
        if (off + 4 > b.size) return null
        val v = b.u32be(off)
        return if (v == 0L || v == 0xFFFFFFFFL) null else Instant.ofEpochSecond(v)
    }

    /** Name : 1 octet de page de codes ISO 8859 + 35 caractères. */
    fun name(b: ByteArray, off: Int, length: Int = 36): String {
        if (off + length > b.size) return ""
        val codePage = b.u8(off)
        val charset = try {
            if (codePage in 1..16) Charset.forName("ISO-8859-$codePage") else Charsets.ISO_8859_1
        } catch (_: Exception) {
            Charsets.ISO_8859_1
        }
        return clean(String(b, off + 1, length - 1, charset))
    }

    /** Chaîne IA5 (ASCII). */
    fun ia5(b: ByteArray, off: Int, length: Int): String {
        if (off + length > b.size) return ""
        return clean(String(b, off, length, Charsets.ISO_8859_1))
    }

    private fun clean(s: String): String = s.filter { it.code in 0x20..0xFE && it.code != 0x7F }.trim()

    fun bcd(b: ByteArray, off: Int, length: Int): Int? {
        var v = 0
        for (i in 0 until length) {
            val x = b.u8(off + i)
            val hi = x shr 4
            val lo = x and 0x0F
            if (hi > 9 || lo > 9) return null
            v = v * 100 + hi * 10 + lo
        }
        return v
    }

    /** Datef : date en BCD AAAAMMJJ (4 octets). */
    fun datef(b: ByteArray, off: Int): LocalDate? {
        if (off + 4 > b.size) return null
        val y = bcd(b, off, 2) ?: return null
        val m = bcd(b, off + 2, 1) ?: return null
        val d = bcd(b, off + 3, 1) ?: return null
        return try { LocalDate.of(y, m, d) } catch (_: Exception) { null }
    }

    /** NationNumeric → code distinctif de pays. */
    fun nation(code: Int): String = NATIONS.getOrNull(code)
        ?: when (code) {
            0xFD -> "EC"
            0xFE -> "EUR"
            0xFF -> "WLD"
            else -> "?%02X".format(code)
        }

    private val NATIONS = listOf(
        "", "A", "AL", "AND", "ARM", "AZ", "B", "BG", "BIH", "BY", "CH", "CY", "CZ", "D", "DK", "E",
        "EST", "F", "FIN", "FL", "FR", "UK", "GE", "GR", "H", "HR", "I", "IRL", "IS", "KZ", "L", "LT",
        "LV", "M", "MC", "MD", "MK", "N", "NL", "P", "PL", "RO", "RSM", "RUS", "S", "SK", "SLO", "TM",
        "TR", "UA", "V", "YU", "MNE", "SRB", "UZ", "TJ",
    )
}
