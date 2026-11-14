package fr.tachoperso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    @Test
    fun allerRetour() {
        val a = Files.createTempDirectory("src").toFile()
        java.io.File(a, "drivers/F_X/reads").mkdirs()
        java.io.File(a, "drivers/F_X/reads/C_1.DDD").writeText("ddd")
        java.io.File(a, "drivers/F_X/settings.json").writeText("{}")
        java.io.File(a, "place-names.json").writeText("{}")
        java.io.File(a, "autre.txt").writeText("hors sauvegarde")
        val out = ByteArrayOutputStream()
        Backup.write(a, out)

        val b = Files.createTempDirectory("dst").toFile()
        java.io.File(b, "drivers/ANCIEN").mkdirs()
        val n = Backup.restore(b, ByteArrayInputStream(out.toByteArray()))
        assertEquals(3, n)
        assertEquals("ddd", java.io.File(b, "drivers/F_X/reads/C_1.DDD").readText())
        assertFalse(java.io.File(b, "drivers/ANCIEN").exists())
        assertFalse(java.io.File(b, "autre.txt").exists())
    }

    @Test
    fun cheminDangereuxRefuse() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("tachoperso-backup.txt")); z.closeEntry()
            z.putNextEntry(ZipEntry("drivers/../../evil.txt")); z.write(1); z.closeEntry()
        }
        val b = Files.createTempDirectory("dst").toFile()
        java.io.File(b, "drivers/GARDE").mkdirs()
        val r = runCatching { Backup.restore(b, ByteArrayInputStream(out.toByteArray())) }
        assertTrue(r.isFailure)
        assertTrue(java.io.File(b, "drivers/GARDE").exists())
    }
}
