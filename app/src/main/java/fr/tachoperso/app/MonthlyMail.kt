package fr.tachoperso.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.tachoperso.core.apdu.CardException
import fr.tachoperso.core.rules.MonthlyReport
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Récapitulatif mensuel envoyé par le conducteur lui-même : l'application prépare un e-mail
 * (destinataire, objet, texte, PDF du mois et dernière lecture .DDD) dans la messagerie du téléphone,
 * il ne reste qu'à appuyer sur « Envoyer ». Aucun compte ni serveur : rien ne sort sans son accord.
 */
object MonthlyMail {
    const val EXTRA_DRIVER = "fr.tachoperso.mail.driver"
    const val EXTRA_MONTH = "fr.tachoperso.mail.month"
    private val PARIS: ZoneId = ZoneId.of("Europe/Paris")
    private val MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)

    fun monthLabel(m: YearMonth): String = MONTH_FMT.format(m)

    /**
     * Mois à proposer : le mois précédent, une fois la carte lue après le 1er du mois en cours
     * (sinon les derniers jours du mois manqueraient au récapitulatif).
     */
    fun dueMonth(lastRead: Instant?, now: Instant = Instant.now(), zone: ZoneId = PARIS): YearMonth? {
        lastRead ?: return null
        val current = YearMonth.from(now.atZone(zone))
        val monthStart = current.atDay(1).atStartOfDay(zone).toInstant()
        return if (lastRead >= monthStart) current.minusMonths(1) else null
    }

    /**
     * Mois écoulé dont le récapitulatif attend encore une lecture de la carte (dernière lecture
     * avant le 1er du mois en cours) : sert à rappeler de vider la carte avant l'envoi.
     */
    fun readFirstMonth(lastRead: Instant?, now: Instant = Instant.now(), zone: ZoneId = PARIS): YearMonth? {
        lastRead ?: return null
        val current = YearMonth.from(now.atZone(zone))
        val previous = current.minusMonths(1)
        val previousStart = previous.atDay(1).atStartOfDay(zone).toInstant()
        val monthStart = current.atDay(1).atStartOfDay(zone).toInstant()
        // Pas de rappel si la carte n'a rien enregistré du mois écoulé (lecture encore plus ancienne :
        // le rappel des 28 jours s'en charge).
        return if (lastRead < monthStart && lastRead >= previousStart) previous else null
    }

    /** Vrai si la carte n'a pas été relue après la fin de [month] : récapitulatif incomplet. */
    fun incomplete(lastRead: Instant?, month: YearMonth, zone: ZoneId = PARIS): Boolean =
        lastRead == null || lastRead < month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()

    /** Prépare les pièces jointes et renvoie l'intention « choisir une messagerie ». */
    fun intent(ctx: Context, key: String, month: YearMonth): Intent {
        val repo = DriverRepository(ctx.filesDir)
        val profile = repo.profile(key) ?: throw CardException("Conducteur introuvable")
        val settings = repo.settings(key)
        val history = repo.history(key)
        val rates = BaremeRepository(ctx).local().rates
        val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }

        val pdf = File(dir, "TachoPerso_recap_$month.pdf")
        val report = MonthlyReport.build(history, history.expenses(rates, settings.abroadMarkup), month)
        val bareme = rates.lastOrNull()?.let { "${it.source} (en vigueur le ${it.effective})" } ?: "indisponible"
        pdf.outputStream().use { ReportPdf.write(it, report, profile.displayName, profile.cardNumber, bareme) }
        val files = mutableListOf(pdf)
        repo.reads(key).firstOrNull()?.let { ddd -> files += ddd.copyTo(File(dir, ddd.name), overwrite = true) }

        val authority = "${ctx.packageName}.files"
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, authority, it) })
        val label = monthLabel(month)
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            if (profile.email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(profile.email))
            putExtra(Intent.EXTRA_SUBJECT, "Tacho Perso : récapitulatif $label")
            putExtra(
                Intent.EXTRA_TEXT,
                "Récapitulatif $label de ${profile.displayName} : heures, frais de route, découchés et ferry " +
                    "(PDF), avec la dernière lecture de la carte conducteur (.DDD) à conserver.\n",
            )
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Envoyer le récapitulatif").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
