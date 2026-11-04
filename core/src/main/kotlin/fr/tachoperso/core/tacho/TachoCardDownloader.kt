package fr.tachoperso.core.tacho

import fr.tachoperso.core.apdu.CardChannel
import fr.tachoperso.core.apdu.CardException
import fr.tachoperso.core.apdu.CommandApdu
import fr.tachoperso.core.apdu.ResponseApdu
import fr.tachoperso.core.util.Log
import fr.tachoperso.core.util.hex

/**
 * Lit l'intégralité d'une carte conducteur, comme le fait un logiciel de téléchargement :
 * SELECT, (PERFORM HASH OF FILE), READ BINARY, (PSO: COMPUTE DIGITAL SIGNATURE).
 * Aucune écriture n'est faite sur la carte. La carte conducteur n'a pas de code PIN.
 */
class TachoCardDownloader(
    private val channel: CardChannel,
    private val log: Log = Log.NONE,
    private val onProgress: (step: String, fraction: Float) -> Unit = { _, _ -> },
) {
    private val warnings = mutableListOf<String>()

    fun download(withSignatures: Boolean = true, atr: ByteArray? = null): CardDump {
        val files = mutableListOf<CardFile>()
        val total = (TachoFiles.MF.size + TachoFiles.GEN1.size + TachoFiles.GEN2.size).toFloat()
        var done = 0

        selectMf()
        for (ef in TachoFiles.MF) {
            onProgress(ef.name, done++ / total)
            readFile(null, ef, false)?.let(files::add)
        }

        for (gen in Generation.entries) {
            val list = TachoFiles.of(gen)
            if (!selectDf(gen)) {
                log.log("Application ${gen.label} absente de la carte")
                done += list.size
                continue
            }
            log.log("Application ${gen.label} sélectionnée")
            for (ef in list) {
                onProgress("${gen.name} ${ef.name}", done++ / total)
                readFile(gen, ef, withSignatures && ef.signed)?.let(files::add)
            }
        }
        onProgress("Terminé", 1f)
        if (files.none { it.generation != null }) {
            throw CardException("Aucune application tachygraphe trouvée : est-ce bien une carte conducteur ?")
        }
        return CardDump(files, atr, warnings.toList())
    }

    private fun selectMf() {
        var r = channel.transmit(CommandApdu(0x00, 0xA4, 0x00, 0x0C, hex("3F00")))
        if (!r.isOk) r = channel.transmit(CommandApdu(0x00, 0xA4, 0x00, 0x0C))
        if (!r.isOk) log.log("SELECT MF : SW %04X".format(r.sw))
    }

    private fun selectDf(gen: Generation): Boolean {
        val r = channel.transmit(CommandApdu(0x00, 0xA4, 0x04, 0x0C, gen.aid))
        return r.isOk
    }

    private fun readFile(gen: Generation?, ef: EfDef, sign: Boolean): CardFile? {
        val fid = byteArrayOf((ef.fid shr 8).toByte(), ef.fid.toByte())
        val sel = channel.transmit(CommandApdu(0x00, 0xA4, 0x02, 0x0C, fid))
        if (!sel.isOk) {
            if (sel.sw != 0x6A82) log.log("SELECT ${ef.name} : SW %04X".format(sel.sw))
            return null
        }
        var hashed = false
        if (sign) {
            val h = channel.transmit(CommandApdu(0x80, 0x2A, 0x90, 0x00))
            hashed = h.isOk
            if (!hashed) warn("Empreinte refusée pour ${ef.name} (SW %04X)".format(h.sw))
        }
        val data = try {
            readBinaryAll()
        } catch (e: CardException) {
            warn("Lecture de ${ef.name} impossible : ${e.message}")
            return null
        }
        var signature: ByteArray? = null
        if (hashed) {
            val le = if (gen == Generation.GEN1) 128 else 256
            val s = channel.transmit(CommandApdu(0x00, 0x2A, 0x9E, 0x9A, le = le))
            if (s.isOk && s.data.isNotEmpty()) signature = s.data
            else warn("Signature refusée pour ${ef.name} (SW %04X)".format(s.sw))
        }
        log.log("${gen?.name ?: "MF"} ${ef.name} : ${data.size} octets" + if (signature != null) " + signature ${signature.size}" else "")
        return CardFile(gen, ef.fid, data, signature)
    }

    private fun readBinaryAll(): ByteArray {
        var out = ByteArray(0)
        var chunk = CHUNK
        var offset = 0
        while (offset <= 0x7FFF) {
            val r: ResponseApdu = channel.transmit(
                CommandApdu(0x00, 0xB0, offset shr 8, offset and 0xFF, le = minOf(chunk, 0x8000 - offset)),
            )
            when {
                r.sw == 0x9000 -> {
                    if (r.data.isEmpty()) break
                    out += r.data
                    offset += r.data.size
                }
                r.sw == 0x6282 -> { out += r.data; break } // fin de fichier atteinte
                r.sw == 0x6B00 || r.sw == 0x6A86 -> break // offset au-delà de la fin
                r.sw == 0x6700 && chunk > 1 -> chunk /= 2 // Le trop grand : on réduit
                else -> throw CardException("READ BINARY à l'offset $offset : SW %04X".format(r.sw))
            }
        }
        return out
    }

    private fun warn(msg: String) {
        warnings += msg
        log.log("⚠ $msg")
    }

    companion object {
        const val CHUNK = 255
    }
}
