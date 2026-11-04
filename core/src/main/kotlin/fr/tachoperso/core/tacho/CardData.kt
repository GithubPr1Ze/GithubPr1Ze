package fr.tachoperso.core.tacho

import fr.tachoperso.core.util.u16be
import fr.tachoperso.core.util.u8
import java.time.Instant
import java.time.LocalDate

/** EF Identification : CardIdentification (65 o) + DriverCardHolderIdentification (78 o). */
data class CardIdentity(
    val issuingNation: String,
    val cardNumber: String,
    val issuingAuthority: String,
    val issueDate: Instant?,
    val validityBegin: Instant?,
    val expiryDate: Instant?,
    val surname: String,
    val firstNames: String,
    val birthDate: LocalDate?,
    val preferredLanguage: String,
) {
    companion object {
        fun parse(d: ByteArray): CardIdentity? {
            if (d.size < 143) return null
            return CardIdentity(
                issuingNation = DataTypes.nation(d.u8(0)),
                cardNumber = DataTypes.ia5(d, 1, 16),
                issuingAuthority = DataTypes.name(d, 17),
                issueDate = DataTypes.timeReal(d, 53),
                validityBegin = DataTypes.timeReal(d, 57),
                expiryDate = DataTypes.timeReal(d, 61),
                surname = DataTypes.name(d, 65),
                firstNames = DataTypes.name(d, 101),
                birthDate = DataTypes.datef(d, 137),
                preferredLanguage = DataTypes.ia5(d, 141, 2),
            )
        }
    }
}

/** EF Driving_Licence_Info (53 o). */
data class DrivingLicence(val authority: String, val nation: String, val number: String) {
    companion object {
        fun parse(d: ByteArray): DrivingLicence? {
            if (d.size < 53) return null
            return DrivingLicence(DataTypes.name(d, 0), DataTypes.nation(d.u8(36)), DataTypes.ia5(d, 37, 16))
        }
    }
}

/** EF Application_Identification (Gen1 : 10 o, Gen2 : 17 o). */
data class ApplicationIdentification(
    val cardType: Int,
    val structureVersion: Int,
    val eventsPerType: Int,
    val faultsPerType: Int,
    val activityStructureLength: Int,
    val vehicleRecords: Int,
    val placeRecords: Int,
) {
    val structureVersionLabel: String get() = "%d.%d".format(structureVersion shr 8, structureVersion and 0xFF)

    companion object {
        fun parse(d: ByteArray, gen: Generation): ApplicationIdentification? {
            if (d.size < 10) return null
            val places = if (gen == Generation.GEN2 && d.size >= 11) d.u16be(9) else d.u8(9)
            return ApplicationIdentification(d.u8(0), d.u16be(1), d.u8(3), d.u8(4), d.u16be(5), d.u16be(7), places)
        }
    }
}

/** EF Current_Usage : dernière session ouverte (heure + immatriculation). */
data class CurrentUsage(val sessionOpenTime: Instant?, val vehicleNation: String, val vehicleRegistration: String) {
    companion object {
        fun parse(d: ByteArray): CurrentUsage? {
            if (d.size < 19) return null
            return CurrentUsage(DataTypes.timeReal(d, 0), DataTypes.nation(d.u8(4)), DataTypes.name(d, 5, 14))
        }
    }
}
