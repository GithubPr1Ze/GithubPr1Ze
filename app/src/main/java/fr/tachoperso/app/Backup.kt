package fr.tachoperso.app

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sauvegarde complète de l'application dans un .zip : toutes les lectures .DDD, fiches conducteur et
 * réglages (corrections, domicile, sociétés, bulletins), noms de lieux. Sert à changer de téléphone
 * ou à réinstaller l'application sans rien perdre.
 */
object Backup {
    private const val MARKER = "tachoperso-backup.txt"
    private val ROOTS = listOf("drivers", "place-names.json", "place-names-4.json")

    fun write(filesDir: File, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write("Sauvegarde Tacho Perso v1\n".toByteArray())
            zip.closeEntry()
            ROOTS.map { File(filesDir, it) }.filter { it.exists() }.forEach { root ->
                root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(filesDir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Restaure une sauvegarde : contrôle le fichier (marqueur, chemins autorisés uniquement), extrait
     * dans un dossier temporaire puis remplace les données actuelles. Renvoie le nombre de fichiers.
     */
    fun restore(filesDir: File, input: InputStream): Int {
        val staging = File(filesDir, "restore.tmp").apply { deleteRecursively(); mkdirs() }
        var marker = false
        var count = 0
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (name == MARKER) { marker = true; continue }
                    require(!e.isDirectory || name.startsWith("drivers/")) { "Contenu inattendu : $name" }
                    if (e.isDirectory) continue
                    require(ROOTS.any { name == it || name.startsWith("$it/") } && ".." !in name.split('/')) {
                        "Fichier refusé dans la sauvegarde : $name"
                    }
                    val target = File(staging, name)
                    require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Chemin refusé : $name" }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
            require(marker) { "Ce fichier n'est pas une sauvegarde Tacho Perso" }
            require(File(staging, "drivers").isDirectory) { "Sauvegarde vide" }
            ROOTS.forEach { r ->
                val src = File(staging, r)
                if (src.exists()) {
                    val dst = File(filesDir, r)
                    dst.deleteRecursively()
                    check(src.renameTo(dst)) { "Impossible de restaurer $r" }
                }
            }
            return count
        } finally {
            staging.deleteRecursively()
        }
    }
}
