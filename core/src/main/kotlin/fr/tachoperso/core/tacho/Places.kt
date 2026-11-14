package fr.tachoperso.core.tacho

import fr.tachoperso.core.util.u24be
import fr.tachoperso.core.util.u8
import java.time.Instant
import kotlin.math.abs

/**
 * Position GNSS (GeoCoordinates, Annexe 1C) : latitude ±DDMM.M et longitude ±DDDMM.M, codées
 * en entiers signés sur 3 octets au dixième de minute (ex. 48°51,4' → 48514).
 */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    /** Affichage degrés-minutes, comme sur les tickets du chronotachygraphe : 48°51,4' N 2°21,1' E. */
    fun label(): String = "${dm(latitude, "N", "S")} ${dm(longitude, "E", "O")}"

    companion object {
        /** Valeurs brutes → position ; null si « non disponible » (90001 / 180001) ou hors bornes. */
        fun fromRaw(lat: Int, lon: Int): GeoPoint? {
            if (abs(lat) > 90000 || abs(lon) > 180000) return null
            if (lat == 0 && lon == 0) return null
            return GeoPoint(decode(lat), decode(lon))
        }

        private fun decode(v: Int): Double {
            val a = abs(v)
            val deg = a / 1000
            val min = (a % 1000) / 10.0
            val r = deg + min / 60.0
            return if (v < 0) -r else r
        }

        private fun dm(v: Double, pos: String, neg: String): String {
            val a = abs(v)
            val deg = a.toInt()
            val min = (a - deg) * 60
            return "%d°%s' %s".format(deg, "%.1f".format(java.util.Locale.FRANCE, min), if (v < 0) neg else pos)
        }
    }
}

/** GNSSPlaceRecord (11 o) : horodatage, précision (m), coordonnées. */
data class GnssFix(val time: Instant?, val accuracyM: Int?, val point: GeoPoint?) {
    companion object {
        const val SIZE = 11

        fun parse(b: ByteArray, off: Int): GnssFix? {
            if (off + SIZE > b.size) return null
            val time = DataTypes.timeReal(b, off)
            val acc = b.u8(off + 4).takeIf { it in 1..100 }
            val point = GeoPoint.fromRaw(s24(b, off + 5), s24(b, off + 8))
            if (time == null && point == null) return null
            return GnssFix(time, acc, point)
        }
    }
}

/** EntryTypeDailyWorkPeriod : début/fin de journée et origine de l'heure. */
enum class PlaceEntryType(val code: Int, val isBegin: Boolean, val label: String) {
    BEGIN(0, true, "Début de journée"),
    END(1, false, "Fin de journée"),
    BEGIN_MANUAL(2, true, "Début de journée (saisie manuelle)"),
    END_MANUAL(3, false, "Fin de journée (saisie manuelle)"),
    BEGIN_ASSUMED(4, true, "Début de journée (supposé par l'appareil)"),
    END_ASSUMED(5, false, "Fin de journée (supposée par l'appareil)"),
    ;

    companion object {
        fun of(code: Int): PlaceEntryType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * PlaceRecord (Gen1 10 o, Gen2 21 o) : pays et compteur saisis en début/fin de journée ;
 * en Gen2 la position GNSS du véhicule à ce moment est jointe.
 */
data class PlaceRecord(
    val time: Instant,
    val type: PlaceEntryType?,
    val country: String,
    val region: Int,
    val odometerKm: Int,
    val gnss: GnssFix?,
) {
    val isBegin: Boolean get() = type?.isBegin ?: false
}

/** GNSSAccumulatedDrivingRecord (18 o) : position enregistrée toutes les 3 h de conduite cumulée. */
data class GnssDrivingRecord(val time: Instant, val gnss: GnssFix?, val odometerKm: Int)

object PlacesParser {
    /** EF Places : pointeur (Gen1 1 o, Gen2 2 o) puis enregistrements circulaires. */
    fun places(d: ByteArray, gen: Generation): List<PlaceRecord> {
        val gen2 = gen == Generation.GEN2
        val header = if (gen2) 2 else 1
        val size = if (gen2) 21 else 10
        val out = mutableListOf<PlaceRecord>()
        var off = header
        while (off + size <= d.size) {
            val time = DataTypes.timeReal(d, off)
            if (time != null) {
                out += PlaceRecord(
                    time = time,
                    type = PlaceEntryType.of(d.u8(off + 4)),
                    country = DataTypes.nation(d.u8(off + 5)),
                    region = d.u8(off + 6),
                    odometerKm = d.u24be(off + 7),
                    gnss = if (gen2) GnssFix.parse(d, off + 10) else null,
                )
            }
            off += size
        }
        return out.sortedBy { it.time }
    }

    /** EF GNSS_Places (Gen2) : pointeur 2 o puis enregistrements de 18 o. */
    fun gnssDriving(d: ByteArray): List<GnssDrivingRecord> {
        val out = mutableListOf<GnssDrivingRecord>()
        var off = 2
        while (off + 18 <= d.size) {
            val time = DataTypes.timeReal(d, off)
            if (time != null) out += GnssDrivingRecord(time, GnssFix.parse(d, off + 4), d.u24be(off + 15))
            off += 18
        }
        return out.sortedBy { it.time }
    }
}

/** Entier signé sur 3 octets (complément à deux). */
internal fun s24(b: ByteArray, off: Int): Int {
    val v = b.u24be(off)
    return if (v and 0x800000 != 0) v - 0x1000000 else v
}
