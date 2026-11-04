package fr.tachoperso.core.ccid

import fr.tachoperso.core.util.toHex

/** Analyse minimale d'un ATR (ISO 7816-3) : protocoles proposés, TA1, mode spécifique, IFSC. */
class Atr(val raw: ByteArray) {
    /** Protocoles annoncés dans l'ordre (le premier est le protocole par défaut). */
    val protocols: List<Int>
    val ta1: Int?
    val tc1: Int?
    /** Présence de TA2 : la carte impose son protocole et ses paramètres (pas de PPS). */
    val specificMode: Boolean
    val specificProtocol: Int?
    val ifsc: Int
    val bwiCwi: Int?
    val inverseConvention: Boolean

    init {
        val protos = mutableListOf<Int>()
        var ta1v: Int? = null
        var tc1v: Int? = null
        var specProto: Int? = null
        var ifscV: Int? = null
        var tb3: Int? = null
        try {
            var i = 1
            var y = (raw[i].toInt() and 0xFF) shr 4
            i++
            var level = 1
            var currentT = 0
            while (true) {
                val ta = if (y and 1 != 0) raw[i++].toInt() and 0xFF else null
                if (y and 2 != 0) {
                    val tb = raw[i++].toInt() and 0xFF
                    if (level >= 3 && currentT == 1 && tb3 == null) tb3 = tb
                }
                val tc = if (y and 4 != 0) raw[i++].toInt() and 0xFF else null
                val td = if (y and 8 != 0) raw[i++].toInt() and 0xFF else null
                if (level == 1) { ta1v = ta; tc1v = tc }
                if (level == 2 && ta != null) specProto = ta and 0x0F
                if (level >= 3 && currentT == 1 && ta != null && ifscV == null) ifscV = ta
                if (td == null) break
                currentT = td and 0x0F
                if (currentT != 15 && currentT !in protos) protos += currentT
                y = td shr 4
                level++
            }
        } catch (_: IndexOutOfBoundsException) {
            // ATR tronqué : on garde ce qu'on a pu lire.
        }
        if (protos.isEmpty()) protos += 0
        protocols = protos
        ta1 = ta1v
        tc1 = tc1v
        specificProtocol = specProto
        specificMode = specProto != null
        ifsc = ifscV ?: 32
        bwiCwi = tb3
        inverseConvention = raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0x3F
    }

    /** Protocole actif sans PPS : celui du mode spécifique, sinon le premier proposé. */
    val defaultProtocol: Int get() = specificProtocol ?: protocols.first()

    override fun toString(): String =
        "ATR ${raw.toHex()} | protocoles T=${protocols.joinToString("/")} | TA1=${ta1?.let { "%02X".format(it) } ?: "-"}" +
            (if (specificMode) " | mode spécifique T=$specificProtocol" else "")
}
