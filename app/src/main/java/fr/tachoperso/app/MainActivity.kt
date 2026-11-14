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
        viewModel.refreshReader() // lecteur branché pendant que l'application est ouverte
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshReader()
    }
}
