package dev.medriid.davit

import android.app.KeyguardManager
import android.graphics.Color
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.medriid.davit.ui.AppScreen

class MainActivity : ComponentActivity() {
    private val repo get() = (application as DavitApp).repo

    /** App lock: true until you unlock; set again after a minute in the background. */
    private var locked by mutableStateOf(true)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // Paper is light in every ink pair, so the bar icons start dark; night print flips them.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        locked = lockWanted()
        setContent { AppScreen(repo, locked = locked && lockWanted(), onUnlock = ::unlock) }
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > 60_000 && lockWanted()) locked = true
        if (locked && lockWanted()) unlock()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        if (!(locked && lockWanted())) repo.onAppOpen()
    }

    private fun lockWanted() = repo.state.value.appLock && canLock(this)

    private fun unlock() {
        if (!canLock(this)) {
            locked = false
            return
        }
        val builder = BiometricPrompt.Builder(this).setTitle("Unlock daVIT")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(), mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                    repo.onAppOpen()
                }
            },
        )
    }

    companion object {
        /** Android 10+ with a screen lock set: otherwise there's nothing to unlock with. */
        fun canLock(activity: ComponentActivity): Boolean =
            Build.VERSION.SDK_INT >= 29 && activity.getSystemService(KeyguardManager::class.java).isDeviceSecure
    }
}
