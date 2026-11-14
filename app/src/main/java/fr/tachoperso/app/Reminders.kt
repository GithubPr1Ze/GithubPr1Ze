package fr.tachoperso.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Rappel affiché dans l'appli et notifié une fois par palier ([key]). */
data class Reminder(val key: String, val title: String, val text: String, val urgent: Boolean)

object Reminders {
    const val READ_WARN_DAYS = 21
    const val READ_LIMIT_DAYS = 28
    private val EXPIRY_STEPS = listOf(90, 60, 30, 15, 7, 1)

    fun due(p: DriverProfile, now: Instant = Instant.now()): List<Reminder> {
        val out = mutableListOf<Reminder>()
        p.lastRead?.let { last ->
            val days = Duration.between(last, now).toDays().toInt()
            if (days >= READ_WARN_DAYS) {
                val bucket = when {
                    days >= READ_LIMIT_DAYS -> "read-28-${(days - READ_LIMIT_DAYS) / 3}"
                    days >= 25 -> "read-25"
                    else -> "read-21"
                }
                out += Reminder(
                    "${p.key}:$bucket",
                    if (days >= READ_LIMIT_DAYS) "Carte non lue depuis $days jours" else "Lecture de la carte à prévoir",
                    "Dernière lecture il y a $days jours. Lisez votre carte avant $READ_LIMIT_DAYS jours pour garder un historique complet.",
                    urgent = days >= READ_LIMIT_DAYS,
                )
            }
        }
        p.expiry?.let { exp ->
            // Date d'expiration enregistrée à 23:59:59 UTC : on compte en jours UTC.
            val today = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
            val days = Duration.between(today, exp).toDays().toInt()
            if (exp < now) {
                out += Reminder("${p.key}:exp-expired", "Carte conducteur expirée", "Votre carte a expiré : vous ne pouvez plus conduire avec.", urgent = true)
            } else {
                val step = EXPIRY_STEPS.lastOrNull { days <= it }
                if (step != null) {
                    out += Reminder(
                        "${p.key}:exp-$step",
                        "Carte conducteur : expire dans $days jour(s)",
                        "Demandez le renouvellement au moins 15 jours ouvrables avant l'expiration (règl. (UE) 165/2014, art. 28).",
                        urgent = days <= 30,
                    )
                }
            }
        }
        return out
    }
}

/** Vérification quotidienne des rappels, notification une seule fois par palier. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val done = prefs.getStringSet("notified", emptySet())!!.toMutableSet()
        val repo = DriverRepository(ctx.filesDir)
        val drivers = repo.drivers()
        drivers.flatMap { Reminders.due(it) }.filter { it.key !in done }.forEach { r ->
            if (notify(ctx, r)) done += r.key
        }
        // Récapitulatif du mois écoulé : une notification par mois, l'envoi se fait d'un appui.
        drivers.forEach { p ->
            MonthlyMail.readFirstMonth(p.lastRead)?.let { m ->
                val r = Reminder(
                    "${p.key}:mail-read-$m",
                    "Lisez votre carte pour le récapitulatif",
                    "Le récapitulatif de ${MonthlyMail.monthLabel(m)} sera prêt à envoyer après une lecture de la carte : " +
                        "les derniers jours du mois n'ont pas encore été lus.",
                    urgent = false,
                )
                if (r.key !in done && notify(ctx, r)) done += r.key
            }
            val m = MonthlyMail.dueMonth(p.lastRead) ?: return@forEach
            val r = Reminder(
                "${p.key}:mail-$m",
                "Récapitulatif de ${MonthlyMail.monthLabel(m)} prêt",
                "Appuyez pour l'envoyer par e-mail" + (if (p.email.isNotBlank()) " à ${p.email}" else "") +
                    " : PDF du mois et dernière lecture de la carte.",
                urgent = false,
            )
            if (r.key !in done && notify(ctx, r) { putExtra(MonthlyMail.EXTRA_DRIVER, p.key); putExtra(MonthlyMail.EXTRA_MONTH, m.toString()) }) {
                done += r.key
            }
        }
        prefs.edit().putStringSet("notified", done).apply()
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "rappels"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("rappels", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun notify(ctx: Context, r: Reminder, extras: Intent.() -> Unit = {}): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels carte conducteur", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                ctx, r.key.hashCode(), Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(r.title)
                .setContentText(r.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            return try {
                NotificationManagerCompat.from(ctx).notify(r.key.hashCode(), n)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
