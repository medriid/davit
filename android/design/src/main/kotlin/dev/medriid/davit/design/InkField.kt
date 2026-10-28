package dev.medriid.davit.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Labelled text field: a 56 dp paper box with a 2 dp ink border that thickens to the
 * ink-A plate colour while focused. [secret] adds a Show/Hide toggle.
 */
@Composable
fun InkField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val inks = LocalInks.current
    var focused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    Column(modifier) {
        InkText(label, label = true)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clip(CardShape)
                .background(inks.paper)
                .border(if (focused) 3.dp else 2.dp, if (focused) inks.a else inks.text, CardShape)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = RisoType.body.copy(fontSize = 18.sp, color = inks.text),
                cursorBrush = SolidColor(inks.a),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp)
                    .onFocusChanged { focused = it.isFocused },
            )
            if (secret) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button) { reveal = !reveal },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (reveal) "HIDE" else "SHOW",
                        style = RisoType.label.copy(color = inks.text),
                    )
                }
            }
        }
    }
}
