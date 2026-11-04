package fr.tachoperso.core

import fr.tachoperso.core.apdu.CardChannel
import fr.tachoperso.core.tacho.ActivityType
import fr.tachoperso.core.tacho.CardReport
import fr.tachoperso.core.tacho.DddFormat
import fr.tachoperso.core.tacho.Generation
import fr.tachoperso.core.tacho.TachoCardDownloader
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TachoTest {
    private fun download(card: FakeTachoCard = FakeTachoCard(), t0: Boolean = true) =
        TachoCardDownloader(CardChannel(card, t0Semantics = t0)).download()

    @Test
    fun `lit toutes les applications et signe les fichiers`() {
        val card = FakeTachoCard()
        val dump = download(card)
        assertTrue(dump.has(Generation.GEN1))
        assertTrue(dump.has(Generation.GEN2))
        assertEquals(600, dump.file(Generation.GEN2, 0x0502)!!.data.size)
        assertEquals(510, dump.file(Generation.GEN2, 0x0505)!!.data.size)
        assertEquals(128, dump.file(Generation.GEN1, 0x0520)!!.signature!!.size)
        assertEquals(64, dump.file(Generation.GEN2, 0x0520)!!.signature!!.size)
        assertNull(dump.file(Generation.GEN2, 0xC101)!!.signature, "les certificats ne sont pas signés")
        assertNull(dump.file(Generation.GEN2, 0x0528), "fichier Gen2v2 absent d'une Gen2 V1")
        assertEquals(25, dump.file(null, 0x0002)!!.data.size)
        assertTrue(dump.warnings.isEmpty(), dump.warnings.toString())
        // Aucune commande d'écriture (UPDATE BINARY = D6) n'a été envoyée.
        assertTrue(card.received.none { (it[1].toInt() and 0xFF) == 0xD6 })
    }

    @Test
    fun `carte Gen2 sans application Gen1`() {
        val dump = download(FakeTachoCard(withGen1 = false))
        assertTrue(!dump.has(Generation.GEN1))
        assertEquals(Generation.GEN2, dump.bestGeneration)
    }

    @Test
    fun `aller-retour au format DDD`() {
        val dump = download()
        val bytes = DddFormat.write(dump)
        val back = DddFormat.read(bytes)
        assertTrue(back.warnings.isEmpty(), back.warnings.toString())
        assertEquals(dump.files.size, back.files.size)
        dump.files.zip(back.files).forEach { (a, b) ->
            assertEquals(a.generation, b.generation)
            assertEquals(a.fid, b.fid)
            assertContentEquals(a.data, b.data)
            assertEquals(a.signature?.toList(), b.signature?.toList())
        }
        assertContentEquals(bytes, DddFormat.write(back))
        // Le fichier commence par EF_ICC, tag 0002 00.
        assertEquals(listOf(0x00, 0x02, 0x00), bytes.take(3).map { it.toInt() })
    }

    @Test
    fun `decode identite permis et activites`() {
        val report = CardReport(download())
        assertEquals("Génération 2 version 1 (Smart V1)", report.generationLabel)
        val id = assertNotNull(report.identity)
        assertEquals("DUPONT", id.surname)
        assertEquals("Jérôme", id.firstNames)
        assertEquals("F", id.issuingNation)
        assertEquals("F1234567890123", id.cardNumber)
        assertEquals(LocalDate.of(1985, 3, 14), id.birthDate)
        assertEquals("123456789012", report.licence!!.number)
        assertEquals("AB-123-CD", report.currentUsage!!.vehicleRegistration)
        assertEquals(1, report.application!!.structureVersion shr 8)

        val days = report.days
        assertEquals(3, days.size)
        assertEquals(FakeTachoCard.DAY1.plusDays(2), days[0].date)
        val d2 = days[1]
        assertEquals(412, d2.distanceKm)
        assertEquals(123, d2.presenceCounter)
        assertEquals((660 - 390) + (960 - 705), d2.minutes(ActivityType.DRIVING))
        assertEquals(30 + 60, d2.minutes(ActivityType.WORK))
        assertEquals(360 + 45 + 420, d2.minutes(ActivityType.REST))
        val d3 = days[0]
        assertEquals(480, d3.minutesCardOut)
        assertEquals(120, d3.minutes(ActivityType.DRIVING))
        assertEquals(60, d3.minutes(ActivityType.AVAILABILITY))
        assertTrue(report.suggestedFileName().endsWith("_J_DUPONT_F1234567890123.DDD"))
    }

    @Test
    fun `fichier DDD invalide`() {
        val dump = DddFormat.read(byteArrayOf(1, 2, 3))
        assertTrue(dump.files.isEmpty())
        assertTrue(dump.warnings.isNotEmpty())
    }
}
