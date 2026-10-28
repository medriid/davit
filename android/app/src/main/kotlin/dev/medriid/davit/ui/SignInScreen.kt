package dev.medriid.davit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.UiState
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Seal

/**
 * The "poster": sign in with VTOP plus three plain promises. The
 * captcha is solved on the phone by the bundled model, so there is nothing to type but
 * the VTOP username and password.
 */
@Composable
internal fun SignInScreen(state: UiState, onSignIn: (String, String) -> Unit) {
    var regNo by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val busy = state.busy != null
    val ready = regNo.isNotBlank() && password.isNotEmpty() && !busy
    val submit = {
        if (ready) {
            focus.clearFocus()
            onSignIn(regNo.trim(), password)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Seal(size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Heading("daVIT", 34.sp, uppercase = false)
        }
        Spacer(Modifier.height(34.dp))
        Heading("Sign in with VTOP", 40.sp)
        Spacer(Modifier.height(18.dp))
        listOf(
            "Stays on this phone" to "Your password is sealed with a key in Android's Keystore.",
            "Talks only to VTOP" to "No server of ours in between. The captcha is solved on the phone.",
            "No accounts, no tracking" to "Nothing to sign up for. No analytics.",
        ).forEach { (title, body) ->
            Row(Modifier.padding(vertical = 5.dp)) {
                InkText("✦", style = RisoType.title, modifier = Modifier.width(26.dp))
                Column {
                    InkText(title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                    InkText(body, style = RisoType.small)
                }
            }
        }
        Spacer(Modifier.height(26.dp))

        InkField(
            value = regNo,
            onValueChange = { regNo = it.filterNot(Char::isWhitespace).uppercase() },
            label = "VTOP username",
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        InkField(
            value = password,
            onValueChange = { password = it },
            label = "VTOP password",
            secret = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        InkButton(state.busy ?: "Sign in", { submit() }, enabled = ready, fillWidth = true, modifier = Modifier.fillMaxWidth())

        AnimatedVisibility(state.error != null && !busy) {
            InkCard(Modifier.fillMaxWidth().padding(top = 18.dp), plateOffset = 4.dp) {
                InkText(state.error.orEmpty())
            }
        }
        Spacer(Modifier.height(18.dp))
        InkText(
            "Wrong passwords aren't retried: VTOP locks accounts after a few failures.",
            style = RisoType.small,
        )
    }
}
