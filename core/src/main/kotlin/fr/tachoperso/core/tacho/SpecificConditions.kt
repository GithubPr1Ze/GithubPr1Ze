package fr.tachoperso.core.tacho

import fr.tachoperso.core.util.u8
import java.time.Duration
import java.time.Instant

/** SpecificConditionType (Annexe 1C). */
enum class SpecificConditionType(val code: Int, val label: String) {
    OUT_OF_SCOPE_BEGIN(1, "Début hors champ"),
    OUT_OF_SCOPE_END(2, "Fin hors champ"),
    FERRY_TRAIN_BEGIN(3, "Début ferry/train"),
    FERRY_TRAIN_END(4, "Fin ferry/train"),
    ;

    companion object {
        fun of(code: Int): SpecificConditionType? = entries.firstOrNull { it.code == code }
    }
}

data class SpecificCondition(val time: Instant, val type: SpecificConditionType)

/**
 * Traversée en ferry ou en train : début, fin (null si non enregistrée) et durée.
 * Une fin à la même minute que le début est conservée : durée 0, signalée comme non mesurée.
 */
data class Crossing(val start: Instant, val end: Instant?) {
    val minutes: Long get() = end?.let { Duration.between(start, it).toMinutes() } ?: 0
    val measured: Boolean get() = minutes > 0
}

object SpecificConditionsParser {
    /** EF Specific_Conditions : Gen1 = 56 × 5 o ; Gen2 = pointeur 2 o puis enregistrements de 5 o. */
    fun parse(d: ByteArray, gen: Generation): List<SpecificCondition> {
        val out = mutableListOf<SpecificCondition>()
        var off = if (gen == Generation.GEN2) 2 else 0
        while (off + 5 <= d.size) {
            val time = DataTypes.timeReal(d, off)
            val type = SpecificConditionType.of(d.u8(off + 4))
            if (time != null && type != null) out += SpecificCondition(time, type)
            off += 5
        }
        return out.sortedWith(compareBy({ it.time }, { it.type.code }))
    }

    /** Associe chaque début ferry/train à la fin qui suit (au plus 24 h plus tard). */
    fun crossings(conditions: List<SpecificCondition>): List<Crossing> {
        val out = mutableListOf<Crossing>()
        var open: Instant? = null
        for (c in conditions.sortedWith(compareBy({ it.time }, { it.type.code }))) {
            when (c.type) {
                SpecificConditionType.FERRY_TRAIN_BEGIN -> {
                    open?.let { out += Crossing(it, null) }
                    open = c.time
                }
                SpecificConditionType.FERRY_TRAIN_END -> {
                    val s = open
                    if (s != null && Duration.between(s, c.time).toHours() < 24) out += Crossing(s, c.time)
                    else if (s != null) out += Crossing(s, null)
                    open = null
                }
                else -> {}
            }
        }
        open?.let { out += Crossing(it, null) }
        return out
    }
}
