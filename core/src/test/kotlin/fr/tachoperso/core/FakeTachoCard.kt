package fr.tachoperso.core

import fr.tachoperso.core.apdu.ApduTransport
import fr.tachoperso.core.tacho.Generation
import fr.tachoperso.core.util.u16be
import fr.tachoperso.core.util.u8
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.ZoneOffset

/** Carte conducteur simulée, réagissant aux APDU comme une vraie carte (comportement T=0). */
class FakeTachoCard(private val withGen1: Boolean = true) : ApduTransport {
    val mf = mutableMapOf<Int, ByteArray>()
    val dfs = mutableMapOf<Generation, MutableMap<Int, ByteArray>>()
    private var currentDf: Generation? = null
    private var currentEf: ByteArray? = null
    private var hashed = false
    val received = mutableListOf<ByteArray>()

    init {
        mf[0x0002] = ByteArray(25) { it.toByte() }
        mf[0x0005] = ByteArray(8) { 0x11 }
        for (gen in Generation.entries) {
            if (gen == Generation.GEN1 && !withGen1) continue
            val files = mutableMapOf<Int, ByteArray>()
            files[0x0501] = if (gen == Generation.GEN2) {
                byteArrayOf(1, 1, 0, 12, 24, 0x35, 0xE8.toByte(), 0, 0xC8.toByte(), 0, 0x70, 0, 0, 0, 0, 0, 0)
            } else {
                byteArrayOf(1, 0, 0, 12, 24, 0x35, 0xE8.toByte(), 0, 0xC8.toByte(), 0x70)
            }
            files[if (gen == Generation.GEN1) 0xC100 else 0xC101] = ByteArray(200) { 0x55 }
            files[0xC108] = ByteArray(200) { 0x66 }
            files[0x0520] = identification()
            files[0x050E] = int32(1_700_000_000)
            files[0x0521] = licence()
            files[0x0504] = activityFile()
            files[0x0507] = currentUsage()
            files[0x0502] = ByteArray(600) { (it % 7).toByte() } // > 2 blocs de lecture
            files[0x0505] = ByteArray(510) // multiple exact de 255
            dfs[gen] = files
        }
    }

    override fun transmitRaw(command: ByteArray): ByteArray {
        received += command
        val ins = command.u8(1)
        val p1 = command.u8(2)
        val p2 = command.u8(3)
        return when (ins) {
            0xA4 -> select(p1, command)
            0x2A -> if (command.u8(0) == 0x80) {
                if (currentEf == null) sw(0x6986) else { hashed = true; sw(0x9000) }
            } else pso(command)
            0xB0 -> readBinary((p1 shl 8) or p2, if (command.size > 4) command.u8(4).let { if (it == 0) 256 else it } else 256)
            0xC0 -> sw(0x6D00)
            else -> sw(0x6D00)
        }
    }

    private fun select(p1: Int, cmd: ByteArray): ByteArray {
        val data = if (cmd.size > 5) cmd.copyOfRange(5, 5 + cmd.u8(4)) else ByteArray(0)
        hashed = false
        when (p1) {
            0x00 -> { currentDf = null; currentEf = null; return sw(0x9000) }
            0x04 -> {
                val gen = Generation.entries.firstOrNull { it.aid.contentEquals(data) && dfs.containsKey(it) }
                    ?: return sw(0x6A82)
                currentDf = gen; currentEf = null
                return sw(0x9000)
            }
            0x02 -> {
                val fid = data.u16be(0)
                val f = (if (currentDf == null) mf else dfs[currentDf]!!)[fid] ?: return sw(0x6A82)
                currentEf = f
                return sw(0x9000)
            }
        }
        return sw(0x6A86)
    }

    private fun readBinary(offset: Int, le: Int): ByteArray {
        val f = currentEf ?: return sw(0x6986)
        if (offset >= f.size) return sw(0x6B00)
        val end = minOf(f.size, offset + le)
        val part = f.copyOfRange(offset, end)
        return part + if (end - offset < le) sw(0x6282) else sw(0x9000)
    }

    private fun pso(cmd: ByteArray): ByteArray {
        if (!hashed) return sw(0x6985)
        val size = if (currentDf == Generation.GEN1) 128 else 64
        val le = if (cmd.size > 4) cmd.u8(4).let { if (it == 0) 256 else it } else 0
        if (le != size) return sw(0x6C00 or size) // comme une carte T=0 : « Le incorrect, voici le bon »
        return ByteArray(size) { 0x5A } + sw(0x9000)
    }

    companion object {
        fun sw(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

        fun int32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()

        fun name(s: String): ByteArray {
            val out = ByteArray(36) { 0x20 }
            out[0] = 1
            s.toByteArray(Charsets.ISO_8859_1).copyInto(out, 1)
            return out
        }

        fun ia5(s: String, len: Int): ByteArray {
            val out = ByteArray(len) { 0x20 }
            s.toByteArray(Charsets.ISO_8859_1).copyInto(out)
            return out
        }

        fun identification(): ByteArray =
            byteArrayOf(0x11) + ia5("F1234567890123", 16) + name("ANTS") +
                int32(1_684_108_800) + int32(1_684_108_800) + int32(1_841_961_600) +
                name("DUPONT") + name("Jérôme") + byteArrayOf(0x19, 0x85.toByte(), 0x03, 0x14) + ia5("fr", 2)

        fun licence(): ByteArray = name("PREFECTURE") + byteArrayOf(0x11) + ia5("123456789012", 16)

        fun currentUsage(): ByteArray = int32(1_700_000_000) + byteArrayOf(0x11) + name("AB-123-CD").copyOf(14)

        fun change(type: Int, minute: Int, cardOut: Boolean = false): Int =
            (if (cardOut) 0x2000 else 0) or (type shl 11) or minute

        /** Enregistrement journalier sérialisé. */
        fun dayRecord(prevLen: Int, date: LocalDate, distance: Int, changes: List<Int>): ByteArray {
            val len = 12 + changes.size * 2
            val bb = ByteBuffer.allocate(len)
            bb.putShort(prevLen.toShort()).putShort(len.toShort())
            bb.putInt(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC).toInt())
            bb.put(0x01).put(0x23).putShort(distance.toShort())
            changes.forEach { bb.putShort(it.toShort()) }
            return bb.array()
        }

        val DAY1: LocalDate = LocalDate.of(2026, 10, 1)

        /**
         * Tampon circulaire de 100 octets contenant 3 jours ; le 3e enregistrement
         * déborde de la fin du tampon et reprend au début.
         */
        fun activityFile(): ByteArray {
            val size = 100
            val buf = ByteArray(size)
            val d1 = dayRecord(0, DAY1, 0, listOf(change(0, 0)))
            val d2 = dayRecord(d1.size, DAY1.plusDays(1), 412, listOf(
                change(0, 0), change(2, 360), change(3, 390), change(0, 660),
                change(3, 705), change(2, 960), change(0, 1020, cardOut = false),
            ))
            val d3 = dayRecord(d2.size, DAY1.plusDays(2), 120, listOf(
                change(0, 0, cardOut = true), change(3, 480), change(1, 600), change(0, 660),
            ))
            val start1 = 50
            val start2 = start1 + d1.size
            val start3 = start2 + d2.size
            for ((start, rec) in listOf(start1 to d1, start2 to d2, start3 to d3)) {
                rec.forEachIndexed { i, b -> buf[(start + i) % size] = b }
            }
            check(start3 + d3.size > size) { "le test doit couvrir le débordement" }
            return byteArrayOf((start1 shr 8).toByte(), start1.toByte(), (start3 shr 8).toByte(), start3.toByte()) + buf
        }
    }
}
