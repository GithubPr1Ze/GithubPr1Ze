package fr.tachoperso.core.tacho

import fr.tachoperso.core.util.u24be
import fr.tachoperso.core.util.u8
import java.time.Instant

/**
 * CardVehicleRecord (Gen1 31 o, Gen2 48 o avec le VIN) : une utilisation d'un véhicule entre
 * l'insertion et le retrait de la carte, avec les compteurs de début et de fin.
 */
data class VehicleUse(
    val nation: String,
    val registration: String,
    val vin: String?,
    val firstUse: Instant,
    val lastUse: Instant?,
    val odometerBegin: Int,
    val odometerEnd: Int,
) {
    /** Clé stable du véhicule : pays + immatriculation. */
    val key: String get() = "$nation $registration"

    /** Distance de l'utilisation, null si le compteur est incohérent. */
    val km: Int? get() = (odometerEnd - odometerBegin).takeIf { it in 0..5000 }
}

object VehiclesParser {
    /** EF Vehicles_Used : pointeur 2 o puis enregistrements circulaires. */
    fun parse(d: ByteArray, gen: Generation): List<VehicleUse> {
        val size = if (gen == Generation.GEN2) 48 else 31
        val out = mutableListOf<VehicleUse>()
        var off = 2
        while (off + size <= d.size) {
            val first = DataTypes.timeReal(d, off + 6)
            val reg = DataTypes.name(d, off + 15, 14)
            if (first != null && reg.isNotBlank()) {
                out += VehicleUse(
                    nation = DataTypes.nation(d.u8(off + 14)),
                    registration = reg,
                    vin = if (gen == Generation.GEN2) DataTypes.ia5(d, off + 31, 17).ifBlank { null } else null,
                    firstUse = first,
                    lastUse = DataTypes.timeReal(d, off + 10),
                    odometerBegin = d.u24be(off),
                    odometerEnd = d.u24be(off + 3),
                )
            }
            off += size
        }
        return out.sortedBy { it.firstUse }
    }
}
