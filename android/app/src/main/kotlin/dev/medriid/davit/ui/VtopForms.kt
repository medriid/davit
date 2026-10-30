package dev.medriid.davit.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.vtop.FormField
import dev.medriid.davit.vtop.Reply
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

// The pieces every VTOP form in the app shares: its fields drawn from what VTOP rendered,
// a review of what will be sent with one Send button, and VTOP's answer.

private val vtopDay = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH)
private val shownDay = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val shownTime = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/**
 * VTOP's labels are often SHOUTED: short ones (names, places) become "Raman K", longer
 * ones a sentence, "VISA (FOR VACATION)" → "Visa (for vacation)".
 */
internal fun sentence(s: String): String = when {
    s.count { it.isUpperCase() } <= s.count { it.isLowerCase() } -> s
    s.trim().split(' ').size <= 3 && '(' !in s -> s.trim().split(' ').filter { it.isNotBlank() }.joinToString(" ") { w -> if (w.length == 1) w else w.lowercase().replaceFirstChar { it.uppercase() } }
    else -> s.trim().lowercase().replaceFirstChar { it.uppercase() }
}

/** VTOP's label without its asides: "Enter Visa Place" → "Visa Place", "Description (about certificate which…)" → "Description". */
internal val FormField.title: String
    get() = label.removePrefix("Please ").removePrefix("Enter ").removePrefix("enter ").removePrefix("Select ").removePrefix("Choose ").replace(Regex("""\s*\([^)]{12,}\)\.*"""), "").trim().ifEmpty { label }

/** A field's value as a person reads it: the option's label, a spelled-out date or time. */
internal fun FormField.shown(value: String): String = when (kind) {
    FormField.Kind.Choice -> options.firstOrNull { it.first == value }?.second?.let(::sentence) ?: value
    FormField.Kind.Date -> runCatching { LocalDate.parse(value).format(shownDay) }.getOrDefault(value)
    FormField.Kind.VtopDate -> runCatching { LocalDate.parse(value, vtopDay).format(shownDay) }.getOrDefault(value)
    FormField.Kind.Time -> runCatching { LocalTime.parse(value).format(shownTime) }.getOrDefault(value)
    else -> value.trim()
}

/** The first thing missing from [values], in the form's words; null when it can be sent. */
internal fun firstProblem(fields: List<FormField>, values: Map<String, String>): String? =
    fields.firstOrNull { it.required && !it.readOnly && values[it.name].orEmpty().isBlank() }?.let {
        if (it.kind == FormField.Kind.Choice) "Pick ${it.title.lowercase()}." else "Fill in ${it.title.lowercase()}."
    }

/** Every field of a VTOP form, each in the app's own control. [onChange] gets the value as VTOP's input would send it. */
@Composable
internal fun FormFields(fields: List<FormField>, values: Map<String, String>, onChange: (String, String) -> Unit) {
    fields.forEachIndexed { i, f ->
        if (i > 0) Spacer(Modifier.height(12.dp))
        val v = values[f.name] ?: f.value
        when (f.kind) {
            FormField.Kind.Choice -> {
                InkText(f.title, label = true)
                Spacer(Modifier.height(4.dp))
                if (f.options.size <= 8) {
                    ChoiceRow(f.options.map { sentence(it.second) }, f.options.indexOfFirst { it.first == v }) { onChange(f.name, f.options[it].first) }
                } else {
                    LongChoice(f, v) { onChange(f.name, it) }
                }
            }
            FormField.Kind.Date, FormField.Kind.VtopDate, FormField.Kind.Time -> WhenField(f, v) { onChange(f.name, it) }
            else -> {
                val max = f.maxLength
                InkField(
                    v,
                    { onChange(f.name, if (max != null) it.take(max) else it) },
                    f.title + (if (max != null && f.kind == FormField.Kind.LongText) " · ${v.length}/$max" else ""),
                    Modifier.fillMaxWidth(),
                    enabled = !f.readOnly,
                    keyboardOptions = if (f.kind == FormField.Kind.Number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                )
            }
        }
    }
}

/** A long list of choices (VTOP's dozens of certificate kinds): one per line until you pick, then just the pick. */
@Composable
private fun LongChoice(f: FormField, value: String, onPick: (String) -> Unit) {
    var open by remember(f.name) { mutableStateOf(value.isEmpty()) }
    if (!open && value.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) { InkChip(f.shown(value), solid = true) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clickable(role = Role.Button) { open = true }) { InkChip("Change") }
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        f.options.forEachIndexed { i, (v, label) ->
            if (i > 0) Hairline()
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onPick(v); open = false }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InkText(sentence(label), Modifier.weight(1f), style = if (v == value) RisoType.body.copy(fontWeight = FontWeight.SemiBold) else RisoType.body)
                if (v == value) InkChip("Picked", solid = true)
            }
        }
    }
}

/** A date or a time, opening the system picker; the value goes in the format VTOP's input uses. */
@Composable
private fun WhenField(f: FormField, value: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        InkText(f.title, label = true, modifier = Modifier.weight(1f))
        Box(Modifier.clickable(role = Role.Button, enabled = !f.readOnly) {
            if (f.kind == FormField.Kind.Time) {
                val t = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(21, 0))
                TimePickerDialog(context, { _, h, m -> onPick(LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("HH:mm"))) }, t.hour, t.minute, false).show()
            } else {
                val iso = f.kind == FormField.Kind.Date
                val d = runCatching { if (iso) LocalDate.parse(value) else LocalDate.parse(value, vtopDay) }.getOrDefault(LocalDate.now())
                DatePickerDialog(context, { _, y, m, day ->
                    val picked = LocalDate.of(y, m + 1, day)
                    onPick(if (iso) picked.toString() else picked.format(vtopDay))
                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            }
        }) { InkChip(if (value.isBlank()) "Pick" else f.shown(value), solid = value.isNotBlank()) }
    }
}

/**
 * The last step before anything reaches VTOP: everything that will be sent, VTOP's own
 * warning if it has one, and one Send. Nothing is sent before that tap.
 */
@Composable
internal fun SendReview(
    question: String,
    lines: List<Pair<String, String>>,
    sendLabel: String,
    sending: Boolean,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    warning: String? = null,
) {
    Column {
        Spacer(Modifier.height(4.dp))
        Rule()
        Spacer(Modifier.height(10.dp))
        InkText(question, label = true)
        Spacer(Modifier.height(6.dp))
        lines.filter { it.second.isNotBlank() }.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                InkText(k, style = RisoType.small, modifier = Modifier.width(110.dp))
                InkText(v, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        if (warning != null) {
            Spacer(Modifier.height(8.dp))
            InkText(warning, style = RisoType.small.copy(color = LocalInks.current.a))
        }
        Spacer(Modifier.height(12.dp))
        InkButton(if (sending) "Sending" else sendLabel, { if (!sending) onSend() }, enabled = !sending, fillWidth = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        InkButton("Edit", onEdit, enabled = !sending, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
    }
}

/** What VTOP said back, in its words, until you tap OK. */
@Composable
internal fun ReplyCard(reply: Reply, onDismiss: () -> Unit) {
    InkCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), plate = if (reply.ok) LocalInks.current.b else LocalInks.current.a, plateOffset = 4.dp) {
        InkText(if (reply.ok) "Sent" else "VTOP said", label = true)
        Spacer(Modifier.height(6.dp))
        InkText(reply.message)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.clickable(role = Role.Button, onClick = onDismiss)) { InkChip("OK") }
    }
}
