package fr.tachoperso.core.apdu

import fr.tachoperso.core.util.Log
import fr.tachoperso.core.util.toHex
import java.io.ByteArrayOutputStream

/**
 * Commande APDU ISO 7816-4 courte.
 * @param le longueur attendue : -1 = absente, 1..256 (256 est encodé 00).
 */
class CommandApdu(
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    val data: ByteArray = ByteArray(0),
    val le: Int = -1,
) {
    init {
        require(data.size <= 255) { "APDU étendue non supportée" }
        require(le == -1 || le in 1..256) { "Le invalide : $le" }
    }

    fun toBytes(includeLe: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(cla); out.write(ins); out.write(p1); out.write(p2)
        if (data.isNotEmpty()) {
            out.write(data.size)
            out.write(data)
        }
        if (includeLe && le >= 0) out.write(if (le == 256) 0 else le)
        return out.toByteArray()
    }

    fun withLe(newLe: Int) = CommandApdu(cla, ins, p1, p2, data, newLe)

    override fun toString(): String = toBytes().toHex()
}

class ResponseApdu(val data: ByteArray, val sw: Int) {
    val sw1: Int get() = sw shr 8
    val sw2: Int get() = sw and 0xFF
    val isOk: Boolean get() = sw == 0x9000

    fun toBytes(): ByteArray = data + byteArrayOf((sw shr 8).toByte(), sw.toByte())

    override fun toString(): String = "%04X (%d octets)".format(sw, data.size)

    companion object {
        fun parse(raw: ByteArray): ResponseApdu {
            if (raw.size < 2) throw CardException("Réponse carte trop courte : ${raw.toHex()}")
            val sw = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
            return ResponseApdu(raw.copyOfRange(0, raw.size - 2), sw)
        }
    }
}

open class CardException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Transport brut : envoie les octets d'une APDU, renvoie les octets de la réponse (données + SW). */
fun interface ApduTransport {
    fun transmitRaw(command: ByteArray): ByteArray
}

/**
 * Canal APDU de haut niveau. Gère les mécanismes T=0 (61xx GET RESPONSE, 6Cxx Le incorrect)
 * qui peuvent remonter même avec un lecteur « niveau APDU ».
 *
 * @param t0Semantics si vrai, une commande cas 4 (données + Le) est envoyée sans Le, comme l'exige T=0.
 */
class CardChannel(
    private val transport: ApduTransport,
    private val t0Semantics: Boolean = false,
    private val log: Log = Log.NONE,
    var verbose: Boolean = false,
) {
    fun transmit(command: CommandApdu): ResponseApdu {
        val firstBytes = if (t0Semantics && command.data.isNotEmpty() && command.le >= 0) {
            command.toBytes(includeLe = false)
        } else {
            command.toBytes()
        }
        var response = exchange(firstBytes)
        if (response.sw1 == 0x6C) {
            val le = if (response.sw2 == 0) 256 else response.sw2
            response = exchange(command.withLe(le).toBytes())
        }
        var data = response.data
        var guard = 0
        while (response.sw1 == 0x61 && guard++ < 128) {
            val le = if (response.sw2 == 0) 256 else response.sw2
            response = exchange(CommandApdu(0x00, 0xC0, 0x00, 0x00, le = le).toBytes())
            data += response.data
        }
        return ResponseApdu(data, response.sw)
    }

    private fun exchange(bytes: ByteArray): ResponseApdu {
        val response = ResponseApdu.parse(transport.transmitRaw(bytes))
        if (verbose) log.log("> ${bytes.toHex()}  < $response")
        return response
    }
}
