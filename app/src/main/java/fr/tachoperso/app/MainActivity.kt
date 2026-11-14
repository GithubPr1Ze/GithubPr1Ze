package fr.tachoperso.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReminderWorker.schedule(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleMail(intent)
        val placeNames = PlaceNames(applicationContext)
        setContent {
            fr.tachoperso.app.ui.TachoTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalPlaceNames provides placeNames) {
                    MainScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMail(intent)
        viewModel.refreshReader() // lecteur branché pendant que l'application est ouverte
    }

    /** Notification « récapitulatif prêt » : ouvre directement la messagerie. */
    private fun handleMail(intent: Intent?) {
        val key = intent?.getStringExtra(MonthlyMail.EXTRA_DRIVER) ?: return
        val month = intent.getStringExtra(MonthlyMail.EXTRA_MONTH)?.let { runCatching { java.time.YearMonth.parse(it) }.getOrNull() } ?: return
        intent.removeExtra(MonthlyMail.EXTRA_DRIVER)
        viewModel.sendMonthly(key, month)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshReader()
    }
}
