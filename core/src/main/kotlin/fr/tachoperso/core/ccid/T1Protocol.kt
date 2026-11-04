package fr.tachoperso.core.ccid

import fr.tachoperso.core.apdu.ApduTransport
import fr.tachoperso.core.apdu.CardException
import fr.tachoperso.core.util.Log
import fr.tachoperso.core.util.toHex
import java.io.ByteArrayOutputStream

/**
 * Implémentation minimale du protocole bloc T=1 (ISO 7816-3 §11), pour les lecteurs CCID
 * de niveau TPDU : le lecteur transporte les blocs, c'est à nous de les construire.
 */
class T1Protocol(
    private val blockExchange: (ByteArray) -> ByteArray,
    private var ifsc: Int,
    private val log: Log = Log.NONE,
) : ApduTransport {
    private var ns = 0 // numéro de séquence de nos blocs I
    private var nr = 0 // numéro de séquence attendu pour les blocs I de la carte

    private class Block(val pcb: Int, val inf: ByteArray) {
        val isI get() = pcb and 0x80 == 0
        val isR get() = pcb and 0xC0 == 0x80
        val isS get() = pcb and 0xC0 == 0xC0
        val more get() = pcb and 0x20 != 0
        val iSeq get() = (pcb shr 6) and 1
        val rSeq get() = (pcb shr 4) and 1
    }

    /** Demande à la carte d'accepter des blocs plus grands (accélère la lecture). */
    fun negotiateIfsd(ifsd: Int = 254) {
        try {
            val resp = rawExchange(0xC1, byteArrayOf(ifsd.toByte()))
            if (resp.pcb != 0xE1) log.log("T=1 : IFSD refusé (PCB %02X)".format(resp.pcb))
        } catch (e: CardException) {
            log.log("T=1 : négociation IFSD impossible (${e.message})")
        }
    }

    override fun transmitRaw(command: ByteArray): ByteArray {
        // 1) Envoi de l'APDU, éventuellement chaînée en plusieurs blocs I.
        var offset = 0
        var response: Block
        while (true) {
            val len = minOf(ifsc, command.size - offset)
            val more = offset + len < command.size
            val chunk = command.copyOfRange(offset, offset + len)
            response = sendIBlock(chunk, more)
            offset += len
            if (!more) break
            if (!response.isR) throw CardException("T=1 : bloc R attendu pendant le chaînage")
        }
        // 2) Réception de la réponse, éventuellement chaînée.
        val out = ByteArrayOutputStream()
        var guard = 0
        while (guard++ < 1024) {
            if (!response.isI) throw CardException("T=1 : bloc inattendu PCB %02X".format(response.pcb))
            nr = nr xor 1
            out.write(response.inf)
            if (!response.more) return out.toByteArray()
            response = withSRequests(rawExchange(0x80 or (nr shl 4), ByteArray(0)))
        }
        throw CardException("T=1 : réponse trop longue")
    }

    private fun sendIBlock(inf: ByteArray, more: Boolean): Block {
        val pcb = (ns shl 6) or (if (more) 0x20 else 0)
        var attempt = 0
        while (true) {
            val resp = withSRequests(rawExchange(pcb, inf))
            // Bloc R avec le même N(R) que notre N(S) = la carte demande une retransmission.
            if (resp.isR && resp.rSeq == ns && attempt++ < 2) {
                log.log("T=1 : retransmission demandée (PCB %02X)".format(resp.pcb))
                continue
            }
            ns = ns xor 1
            return resp
        }
    }

    /** Répond aux requêtes S de la carte (extension de délai WTX, changement IFS). */
    private fun withSRequests(first: Block): Block {
        var block = first
        var guard = 0
        while (block.isS && guard++ < 64) {
            when (block.pcb and 0x3F) {
                0x03 -> block = rawExchange(0xE3, block.inf) // WTX
                0x01 -> { // IFS
                    if (block.inf.isNotEmpty()) ifsc = block.inf[0].toInt() and 0xFF
                    block = rawExchange(0xE1, block.inf)
                }
                else -> throw CardException("T=1 : bloc S non géré PCB %02X".format(block.pcb))
            }
        }
        return block
    }

    private fun rawExchange(pcb: Int, inf: ByteArray): Block {
        val frame = ByteArray(3 + inf.size + 1)
        frame[0] = 0 // NAD
        frame[1] = pcb.toByte()
        frame[2] = inf.size.toByte()
        inf.copyInto(frame, 3)
        frame[frame.size - 1] = lrc(frame, frame.size - 1).toByte()
        val resp = blockExchange(frame)
        if (resp.size < 4) throw CardException("T=1 : bloc trop court ${resp.toHex()}")
        val len = resp[2].toInt() and 0xFF
        if (resp.size < 4 + len) throw CardException("T=1 : bloc tronqué ${resp.toHex()}")
        if (lrc(resp, 3 + len) != (resp[3 + len].toInt() and 0xFF)) {
            throw CardException("T=1 : erreur de contrôle LRC")
        }
        return Block(resp[1].toInt() and 0xFF, resp.copyOfRange(3, 3 + len))
    }

    companion object {
        fun lrc(bytes: ByteArray, length: Int): Int {
            var x = 0
            for (i in 0 until length) x = x xor (bytes[i].toInt() and 0xFF)
            return x
        }
    }
}
