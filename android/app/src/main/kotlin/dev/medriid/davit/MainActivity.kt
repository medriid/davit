package dev.medriid.davit

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.medriid.davit.ui.AppScreen

class MainActivity : ComponentActivity() {
    private val repo get() = (application as DavitApp).repo

    override fun onCreate(savedInstanceState: Bundle?) {
        // Paper is light in every ink pair, so the bar icons stay dark.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        setContent { AppScreen(repo) }
    }

    override fun onResume() {
        super.onResume()
        repo.onAppOpen()
    }
}
