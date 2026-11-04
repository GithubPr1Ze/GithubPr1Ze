package fr.tachoperso.core.ccid

import fr.tachoperso.core.apdu.CardChannel
import fr.tachoperso.core.apdu.CardException
import fr.tachoperso.core.util.Log
import fr.tachoperso.core.util.putU32le
import fr.tachoperso.core.util.toHex
import fr.tachoperso.core.util.u32le
import fr.tachoperso.core.util.u8
import java.io.ByteArrayOutputStream

/** Accès brut aux deux points de terminaison « bulk » du lecteur (fourni par Android). */
interface UsbBulkPipe {
    fun write(data: ByteArray, timeoutMs: Int)

    /** Lit jusqu'à [maxLength] octets ; renvoie un tableau vide en cas de délai dépassé. */
    fun read(maxLength: Int, timeoutMs: Int): ByteArray
}

class CcidException(message: String) : CardException(message)

enum class ExchangeLevel(val label: String) {
    CHARACTER("caractère"),
    TPDU("TPDU"),
    SHORT_APDU("APDU courte"),
    EXTENDED_APDU("APDU étendue"),
}

/** Descripteur de classe CCID (USB CCID rev 1.1 §5.1), lu depuis les descripteurs bruts. */
class CcidDescriptor(val protocols: Long, val features: Long, val maxMessageLength: Int) {
    val exchangeLevel: ExchangeLevel
        get() = when {
            features and 0x40000L != 0L -> ExchangeLevel.EXTENDED_APDU
            features and 0x20000L != 0L -> ExchangeLevel.SHORT_APDU
            features and 0x10000L != 0L -> ExchangeLevel.TPDU
            else -> ExchangeLevel.CHARACTER
        }

    /** Le lecteur configure seul les paramètres à partir de l'ATR. */
    val autoParameters: Boolean get() = features and 0x02L != 0L

    /** Le lecteur gère seul la négociation PPS. */
    val autoPps: Boolean get() = features and 0xC0L != 0L

    override fun toString(): String =
        "CCID features=%08X niveau=%s protocoles=%X maxMsg=%d".format(features, exchangeLevel.label, protocols, maxMessageLength)

    companion object {
        fun parse(rawDescriptors: ByteArray?): CcidDescriptor? {
            if (rawDescriptors == null) return null
            var i = 0
            while (i + 1 < rawDescriptors.size) {
                val len = rawDescriptors.u8(i)
                if (len == 0) break
                val type = rawDescriptors.u8(i + 1)
                if (type == 0x21 && len >= 0x36 && i + len <= rawDescriptors.size) {
                    return CcidDescriptor(
                        protocols = rawDescriptors.u32le(i + 6),
                        features = rawDescriptors.u32le(i + 40),
                        maxMessageLength = rawDescriptors.u32le(i + 44).toInt(),
                    )
                }
                i += len
            }
            return null
        }
    }
}

/** Pilote CCID minimal : mise sous tension, paramètres, échange de blocs. */
class CcidReader(
    private val pipe: UsbBulkPipe,
    val descriptor: CcidDescriptor?,
    private val log: Log = Log.NONE,
    private val timeoutMs: Int = 15_000,
) {
    private var seq = 0
    var atr: Atr? = null
        private set

    class Message(val type: Int, val seq: Int, val status: Int, val error: Int, val param: Int, val data: ByteArray) {
        val iccStatus get() = status and 0x03
        val commandStatus get() = (status shr 6) and 0x03
    }

    fun powerOn(): Atr {
        try { exchange(0x63, ByteArray(0)) } catch (_: CcidException) { /* déjà hors tension */ }
        val msg = exchange(0x62, ByteArray(0))
        if (msg.data.isEmpty()) throw CcidException("ATR vide : la carte ne répond pas")
        return Atr(msg.data).also { atr = it }
    }

    fun powerOff() {
        try { exchange(0x63, ByteArray(0)) } catch (_: Exception) { }
    }

    /** Protocole actuellement configuré par le lecteur (PC_to_RDR_GetParameters). */
    fun currentProtocol(): Int? = try {
        exchange(0x6C, ByteArray(0)).param
    } catch (e: CcidException) {
        log.log("GetParameters indisponible : ${e.message}")
        null
    }

    fun setParameters(protocol: Int, atr: Atr, fiDi: Int) {
        val inverse = if (atr.inverseConvention) 0x02 else 0x00
        val guard = atr.tc1 ?: 0
        val data = if (protocol == 0) {
            byteArrayOf(fiDi.toByte(), inverse.toByte(), guard.toByte(), 0x0A, 0x00)
        } else {
            byteArrayOf(
                fiDi.toByte(), (0x10 or inverse).toByte(), guard.toByte(),
                (atr.bwiCwi ?: 0x4D).toByte(), 0x00, atr.ifsc.toByte(), 0x00,
            )
        }
        exchange(0x61, data, p7 = protocol)
    }

    /** PC_to_RDR_XfrBlock : envoie un bloc (APDU ou TPDU selon le niveau du lecteur). */
    fun transfer(block: ByteArray): ByteArray = exchange(0x6F, block).data

    /**
     * Met la carte sous tension et renvoie un canal APDU adapté au lecteur
     * (APDU direct, TPDU T=0 ou TPDU T=1).
     */
    fun openChannel(): CardChannel {
        val atr = powerOn()
        log.log(atr.toString())
        log.log(descriptor?.toString() ?: "Descripteur CCID introuvable : on suppose un lecteur niveau APDU")
        val level = descriptor?.exchangeLevel ?: ExchangeLevel.SHORT_APDU
        return when (level) {
            ExchangeLevel.SHORT_APDU, ExchangeLevel.EXTENDED_APDU ->
                CardChannel({ transfer(it) }, t0Semantics = false, log = log)

            ExchangeLevel.TPDU -> {
                var protocol = atr.defaultProtocol
                if (descriptor?.autoParameters != true) {
                    val fiDi = if (atr.specificMode || descriptor?.autoPps == true) atr.ta1 ?: 0x11 else 0x11
                    try {
                        setParameters(protocol, atr, fiDi)
                    } catch (e: CcidException) {
                        log.log("SetParameters refusé : ${e.message}")
                    }
                }
                currentProtocol()?.let { protocol = it }
                log.log("Lecteur TPDU, protocole T=$protocol")
                if (protocol == 1) {
                    val t1 = T1Protocol({ transfer(it) }, atr.ifsc, log)
                    t1.negotiateIfsd()
                    CardChannel(t1, t0Semantics = false, log = log)
                } else {
                    CardChannel({ transfer(it) }, t0Semantics = true, log = log)
                }
            }

            ExchangeLevel.CHARACTER -> throw CcidException(
                "Ce lecteur fonctionne au niveau caractère, non supporté. Essayez un autre lecteur CCID.",
            )
        }
    }

    private fun exchange(type: Int, data: ByteArray, p7: Int = 0, p8: Int = 0, p9: Int = 0): Message {
        val s = seq
        seq = (seq + 1) and 0xFF
        val msg = ByteArray(10 + data.size)
        msg[0] = type.toByte()
        msg.putU32le(1, data.size)
        msg[5] = 0
        msg[6] = s.toByte()
        msg[7] = p7.toByte()
        msg[8] = p8.toByte()
        msg[9] = p9.toByte()
        data.copyInto(msg, 10)
        pipe.write(msg, timeoutMs)
        var guard = 0
        while (guard++ < 1000) {
            val r = readMessage()
            if (r.seq != s) {
                log.log("CCID : réponse de séquence %d ignorée (attendu %d)".format(r.seq, s))
                continue
            }
            when (r.commandStatus) {
                0 -> return r
                2 -> continue // demande de délai supplémentaire
                else -> throw CcidException(describeError(r))
            }
        }
        throw CcidException("CCID : trop de demandes de délai")
    }

    private fun readMessage(): Message {
        val buffer = ByteArrayOutputStream()
        var expected = -1
        val readSize = (descriptor?.maxMessageLength?.takeIf { it in 64..65544 } ?: 4096).coerceAtMost(16384)
        while (expected < 0 || buffer.size() < expected) {
            val chunk = pipe.read(readSize, timeoutMs)
            if (chunk.isEmpty()) throw CcidException("Le lecteur ne répond pas (délai dépassé)")
            buffer.write(chunk)
            if (expected < 0 && buffer.size() >= 10) {
                expected = 10 + buffer.toByteArray().u32le(1).toInt()
            }
        }
        val all = buffer.toByteArray()
        return Message(
            type = all.u8(0), seq = all.u8(6), status = all.u8(7), error = all.u8(8), param = all.u8(9),
            data = all.copyOfRange(10, expected),
        )
    }

    private fun describeError(m: Message): String {
        if (m.iccStatus == 2) return "Aucune carte dans le lecteur"
        val reason = when (m.error) {
            0xFE -> "carte muette (mal insérée ou à l'envers ?)"
            0xFD -> "erreur de parité"
            0xFB -> "erreur matérielle"
            0xF8, 0xF7 -> "ATR invalide"
            0xF6 -> "protocole non supporté"
            0xF4 -> "conflit d'octet de procédure"
            0xEF -> "classe de tension non supportée"
            0xE0 -> "lecteur occupé"
            else -> "code %02X".format(m.error)
        }
        return "Erreur lecteur (commande %02X) : %s".format(m.type, reason) +
            if (m.data.isNotEmpty()) " [${m.data.toHex()}]" else ""
    }
}
