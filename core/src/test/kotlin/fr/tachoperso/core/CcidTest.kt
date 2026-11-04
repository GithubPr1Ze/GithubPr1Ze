package fr.tachoperso.core

import fr.tachoperso.core.ccid.Atr
import fr.tachoperso.core.ccid.CcidDescriptor
import fr.tachoperso.core.ccid.CcidReader
import fr.tachoperso.core.ccid.ExchangeLevel
import fr.tachoperso.core.ccid.T1Protocol
import fr.tachoperso.core.ccid.UsbBulkPipe
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.TachoCardDownloader
import fr.tachoperso.core.util.hex
import fr.tachoperso.core.util.putU32le
import fr.tachoperso.core.util.u32le
import fr.tachoperso.core.util.u8
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lecteur CCID simulé : découpe ses réponses en paquets de 64 octets et envoie des demandes de délai. */
class FakeCcidPipe(private val handler: (ByteArray) -> ByteArray, private val atr: ByteArray, private val protocol: Int) : UsbBulkPipe {
    private val pending = ArrayDeque<ByteArray>()

    override fun write(data: ByteArray, timeoutMs: Int) {
        val type = data.u8(0)
        val seq = data.u8(6)
        val len = data.u32le(1).toInt()
        val payload = data.copyOfRange(10, 10 + len)
        fun reply(rtype: Int, body: ByteArray, status: Int = 0, param: Int = 0) {
            val msg = ByteArray(10 + body.size)
            msg[0] = rtype.toByte(); msg.putU32le(1, body.size); msg[6] = seq.toByte()
            msg[7] = status.toByte(); msg[9] = param.toByte()
            body.copyInto(msg, 10)
            msg.toList().chunked(64).forEach { pending.addLast(it.toByteArray()) }
        }
        when (type) {
            0x62 -> reply(0x80, atr)
            0x63 -> reply(0x81, ByteArray(0), status = 1)
            0x6C -> reply(0x82, ByteArray(5), param = protocol)
            0x61 -> reply(0x82, ByteArray(5), param = data.u8(7))
            0x6F -> {
                reply(0x80, ByteArray(0), status = 0x80) // demande de délai
                reply(0x80, handler(payload))
            }
            else -> reply(0x81, ByteArray(0), status = 0x40)
        }
    }

    override fun read(maxLength: Int, timeoutMs: Int): ByteArray = pending.removeFirstOrNull() ?: ByteArray(0)
}

/** Carte T=1 simulée (IFSC carte 32, découpe ses réponses en blocs chaînés, envoie un WTX). */
class FakeT1Card(private val apdu: (ByteArray) -> ByteArray) {
    private val incoming = ByteArrayOutputStream()
    private var outgoing = ArrayDeque<ByteArray>()
    private var cardNs = 0
    private var wtxSent = false

    fun block(frame: ByteArray): ByteArray {
        assertEquals(T1Protocol.lrc(frame, frame.size - 1), frame.u8(frame.size - 1), "LRC hôte")
        val pcb = frame.u8(1)
        val inf = frame.copyOfRange(3, 3 + frame.u8(2))
        return when {
            pcb == 0xC1 -> make(0xE1, inf)
            pcb == 0xE3 -> nextI()
            pcb and 0x80 == 0 -> {
                incoming.write(inf)
                val hostNs = (pcb shr 6) and 1
                if (pcb and 0x20 != 0) return make(0x80 or ((hostNs xor 1) shl 4), ByteArray(0))
                val response = apdu(incoming.toByteArray())
                incoming.reset()
                outgoing = ArrayDeque(response.toList().chunked(32).map { it.toByteArray() })
                if (!wtxSent) { wtxSent = true; return make(0xC3, byteArrayOf(2)) }
                nextI()
            }
            pcb and 0xC0 == 0x80 -> nextI()
            else -> error("PCB inattendu %02X".format(pcb))
        }
    }

    private fun nextI(): ByteArray {
        val chunk = outgoing.removeFirst()
        val more = outgoing.isNotEmpty()
        val pcb = (cardNs shl 6) or (if (more) 0x20 else 0)
        cardNs = cardNs xor 1
        return make(pcb, chunk)
    }

    private fun make(pcb: Int, inf: ByteArray): ByteArray {
        val f = byteArrayOf(0, pcb.toByte(), inf.size.toByte()) + inf + byteArrayOf(0)
        f[f.size - 1] = T1Protocol.lrc(f, f.size - 1).toByte()
        return f
    }
}

class CcidTest {
    private fun descriptor(features: Long): ByteArray {
        val d = ByteArray(0x36)
        d[0] = 0x36; d[1] = 0x21
        d.putU32le(6, 3); d.putU32le(40, features.toInt()); d.putU32le(44, 271)
        // précédé d'un descripteur d'interface classe 0B
        return byteArrayOf(9, 4, 0, 0, 3, 0x0B, 0, 0, 0) + d
    }

    @Test
    fun `analyse du descripteur CCID`() {
        assertEquals(ExchangeLevel.EXTENDED_APDU, CcidDescriptor.parse(descriptor(0x000404BA))!!.exchangeLevel)
        assertEquals(ExchangeLevel.TPDU, CcidDescriptor.parse(descriptor(0x000100BA))!!.exchangeLevel)
    }

    @Test
    fun `analyse ATR`() {
        val atr = Atr(hex("3B FF 96 00 00 81 31 FE 43 80 31 80 65 B0 84 56 51 10 12 0F FE 82 90 00 85"))
        assertEquals(listOf(1), atr.protocols)
        assertEquals(0x96, atr.ta1)
        assertEquals(0xFE, atr.ifsc)
        val t0 = Atr(hex("3B 9F 96 80 1F C3 80 31 E0 73 FE 21 1B 63 3A 20 4E 83 00 90 00 31"))
        assertEquals(listOf(0, 15).filter { it != 15 }, t0.protocols)
    }

    @Test
    fun `lecture complete via lecteur niveau APDU`() {
        val card = FakeTachoCard()
        val desc = CcidDescriptor.parse(descriptor(0x000404BA))
        val reader = CcidReader(FakeCcidPipe(card::transmitRaw, hex("3B 9F 96 80 1F C3 80 31 E0"), 0), desc)
        val dump = TachoCardDownloader(reader.openChannel()).download()
        assertEquals("DUPONT", CardReport(dump).identity!!.surname)
        assertTrue(dump.warnings.isEmpty(), dump.warnings.toString())
    }

    @Test
    fun `lecture complete via lecteur TPDU en T=0`() {
        val card = FakeTachoCard()
        val desc = CcidDescriptor.parse(descriptor(0x000100BA))
        val reader = CcidReader(FakeCcidPipe(card::transmitRaw, hex("3B 9F 96 80 1F C3 80 31 E0"), 0), desc)
        val dump = TachoCardDownloader(reader.openChannel()).download()
        assertEquals(3, CardReport(dump).days.size)
        assertTrue(dump.warnings.isEmpty(), dump.warnings.toString())
    }

    @Test
    fun `lecture complete via lecteur TPDU en T=1`() {
        val card = FakeTachoCard()
        // La carte T=1 répond directement avec la bonne longueur (pas de 6Cxx en T=1).
        val t1card = FakeT1Card { apdu ->
            var r = card.transmitRaw(apdu)
            if (r.size == 2 && r.u8(0) == 0x6C) r = card.transmitRaw(apdu.copyOf(apdu.size - 1) + byteArrayOf(r[1]))
            r
        }
        val desc = CcidDescriptor.parse(descriptor(0x000100BA))
        val atr = hex("3B FF 96 00 00 81 31 20 43 80 31 80 65 B0 84 56 51 10 12 0F FE 82 90 00 85")
        val reader = CcidReader(FakeCcidPipe(t1card::block, atr, 1), desc)
        val dump = TachoCardDownloader(reader.openChannel()).download()
        val report = CardReport(dump)
        assertEquals("Jérôme", report.identity!!.firstNames)
        assertEquals(600, dump.files.first { it.fid == 0x0502 }.data.size)
        assertTrue(dump.warnings.isEmpty(), dump.warnings.toString())
    }
}
