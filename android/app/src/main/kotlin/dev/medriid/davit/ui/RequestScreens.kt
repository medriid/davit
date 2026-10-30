package dev.medriid.davit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.vtop.FormField
import dev.medriid.davit.vtop.FormPage
import dev.medriid.davit.vtop.Reply
import dev.medriid.davit.vtop.UserConsent
import kotlinx.coroutines.launch

// Requests to VTOP's offices: each reads VTOP's own form, shows it whole before sending,
// and sends only on the confirm tap.

/**
 * Bonafide certificate: pick the kind (VTOP's own list, read when you open this), fill in
 * what that kind needs (read from VTOP when you pick it), review, apply.
 */
@Composable
internal fun BonafideScreen(route: Route) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var kinds by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var code by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<FormPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<Reply?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }

    fun loadKinds() {
        loading = true
        error = null
        scope.launch {
            runCatching { repo.onVtop { c -> c.bonafideServices() } }.onSuccess { kinds = it }.onFailure { error = repo.explain(it) }
            loading = false
        }
    }
    fun pick(c: String) {
        code = c
        page = null
        reviewing = false
        loading = true
        error = null
        scope.launch {
            runCatching { repo.onVtop { v -> v.bonafideForm(c) } }
                .onSuccess { p -> page = p; values.clear(); p.form?.fields?.forEach { values[it.name] = it.value } }
                .onFailure { error = repo.explain(it) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (kinds == null) loadKinds() }

    RouteScreen(route, "Bonafide", "Apply for a bonafide certificate on VTOP. It goes only after you review it and tap Apply.") {
        reply?.let { ReplyCard(it) { reply = null } }
        error?.let { ErrorNote(it) { if (kinds == null) loadKinds() else if (code.isNotEmpty()) pick(code) } }
        val k = kinds
        if (k == null) {
            if (loading) Loading("Reading VTOP's bonafide form")
            return@RouteScreen
        }
        Spacer(Modifier.height(8.dp))
        FormFields(listOf(FormField("certificateCode", "What it's for", FormField.Kind.Choice, code, k)), mapOf("certificateCode" to code)) { _, v -> pick(v) }
        val form = page?.form
        if (code.isNotEmpty() && form == null) {
            Spacer(Modifier.height(10.dp))
            if (loading) Loading("Opening that form on VTOP") else page?.let { InkText(it.message.ifBlank { "VTOP didn't open a form for this." }, style = RisoType.small) }
        }
        if (form == null) return@RouteScreen
        val fields = form.fields.filter { it.name != "certificateCode" }
        val problem = firstProblem(fields.map { it.copy(required = true) }, values)
        GroupLabel("Details")
        InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Vtop, plateOffset = 4.dp) {
            FormFields(fields, values) { name, v -> values[name] = v; reviewing = false }
            if (fields.any { it.readOnly }) {
                Spacer(Modifier.height(8.dp))
                InkText("VTOP sets the locked dates for this kind.", style = RisoType.small.copy(color = LocalInks.current.rule))
            }
            Spacer(Modifier.height(14.dp))
            if (problem != null) InkText(problem, style = RisoType.small.copy(color = LocalInks.current.a))
            AnimatedVisibility(!reviewing) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    InkButton("Review", { reviewing = true; haptics.tick() }, enabled = problem == null, fillWidth = true, modifier = Modifier.fillMaxWidth())
                }
            }
            AnimatedVisibility(reviewing && problem == null) {
                SendReview(
                    "Apply for this bonafide on VTOP?",
                    listOf("Service" to sentence(k.firstOrNull { it.first == code }?.second.orEmpty())) + fields.map { it.title to it.shown(values[it.name].orEmpty()) },
                    "Apply on VTOP",
                    sending,
                    onSend = {
                        sending = true
                        haptics.heavy()
                        val sent = values.toMap()
                        val kind = code
                        scope.launch {
                            runCatching { repo.onVtop { c -> c.applyBonafide(UserConsent("bonafide $kind"), kind, sent) } }
                                .onSuccess {
                                    reply = it
                                    if (it.ok) { haptics.confirm(); code = ""; page = null; reviewing = false } else haptics.reject()
                                }
                                .onFailure { haptics.reject(); reply = Reply(false, repo.explain(it)) }
                            sending = false
                        }
                    },
                    onEdit = { reviewing = false },
                )
            }
        }
    }
}

/**
 * Course Feedback 24x7: a note to each class's teacher, any time in the semester. VTOP keeps
 * it for good once sent, so each one is shown back before Send.
 */
@Composable
internal fun CourseFeedbackScreen(route: Route) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var semester by remember { mutableStateOf<dev.medriid.davit.vtop.Semester?>(null) }
    var classes by remember { mutableStateOf<List<dev.medriid.davit.vtop.ClassFeedback>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf<Reply?>(null) }

    fun load() {
        loading = true
        error = null
        scope.launch {
            runCatching {
                repo.onVtop { c ->
                    val sem = c.feedbackSemesters().firstOrNull() ?: throw dev.medriid.davit.vtop.Refused("VTOP isn't taking course feedback right now.")
                    sem to c.classFeedback(sem.id)
                }
            }.onSuccess { (s, list) -> semester = s; classes = list }.onFailure { error = repo.explain(it) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    RouteScreen(
        route, "Course feedback", semester?.name ?: "A note to each class's teacher, any time. VTOP keeps it as sent.",
        loading && classes != null, { load() },
    ) {
        reply?.let { ReplyCard(it) { reply = null } }
        error?.let { ErrorNote(it) { load() } }
        val list = classes
        if (list == null) {
            if (loading) Loading("Reading your classes from VTOP")
            return@RouteScreen
        }
        InkText("Say what worked or didn't and why: VTOP asks for specific, polite feedback, and it can't be changed once sent.", style = RisoType.small)
        if (list.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            InkText("VTOP lists no classes for feedback.")
        }
        list.forEachIndexed { i, cls ->
            Spacer(Modifier.height(if (i == 0) 14.dp else 10.dp))
            ClassFeedbackCard(cls, semester?.id.orEmpty()) { r ->
                reply = r
                if (r.ok) load()
            }
        }
    }
}

@Composable
private fun ClassFeedbackCard(cls: dev.medriid.davit.vtop.ClassFeedback, semesterId: String, onReply: (Reply) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var text by remember(cls.classId) { mutableStateOf("") }
    var reviewing by remember(cls.classId) { mutableStateOf(false) }
    var sending by remember(cls.classId) { mutableStateOf(false) }
    InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Classes, plateOffset = 3.dp) {
        InkText(cls.title.ifBlank { cls.code }, style = RisoType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 2)
        InkText(listOf(cls.code, cls.type, cls.slot).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small)
        if (cls.faculty.isNotBlank()) InkText(personName(cls.faculty.substringBefore(" - ")), style = RisoType.small)
        Spacer(Modifier.height(10.dp))
        if (!cls.open) {
            InkText(if (cls.given.isNotBlank()) "Sent: ${cls.given}" else "Feedback sent.", style = RisoType.small.copy(color = LocalInks.current.rule))
            return@InkCard
        }
        dev.medriid.davit.design.InkField(text, { text = it.take(cls.maxLength); reviewing = false }, "Your feedback · ${text.length}/${cls.maxLength}", Modifier.fillMaxWidth())
        AnimatedVisibility(!reviewing && text.isNotBlank()) {
            Column {
                Spacer(Modifier.height(10.dp))
                InkButton("Review", { reviewing = true; haptics.tick() }, fillWidth = true, modifier = Modifier.fillMaxWidth())
            }
        }
        AnimatedVisibility(reviewing) {
            SendReview(
                "Send this to VTOP?",
                listOf("Class" to listOf(cls.code, cls.type).filter { it.isNotBlank() }.joinToString(" · "), "Feedback" to text.trim()),
                "Send feedback",
                sending,
                warning = "VTOP won't let you change or delete it afterwards.",
                onSend = {
                    sending = true
                    haptics.heavy()
                    val words = text
                    scope.launch {
                        runCatching { repo.onVtop { c -> c.submitClassFeedback(UserConsent("course feedback ${cls.classId}"), semesterId, cls.classId, words) } }
                            .onSuccess { if (it.ok) { haptics.confirm(); text = "" } else haptics.reject(); reviewing = false; onReply(it) }
                            .onFailure { haptics.reject(); onReply(Reply(false, repo.explain(it))) }
                        sending = false
                    }
                },
                onEdit = { reviewing = false },
            )
        }
    }
}

/**
 * Paper seeing or re-evaluation: VTOP's choices (semester, service, regular or makeup), the
 * courses it offers for them, then a review that says plainly VTOP will raise a fee invoice.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PaperSeeingScreen(route: Route) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var form by remember { mutableStateOf<FormPage?>(null) }
    var courses by remember { mutableStateOf<dev.medriid.davit.vtop.PaperSeeingPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<Reply?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    val chosen = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    fun loadForm() {
        loading = true
        error = null
        scope.launch {
            runCatching { repo.onVtop { c -> c.paperSeeingForm() } }
                .onSuccess { p -> form = p; p.form?.fields?.forEach { f -> values.putIfAbsent(f.name, f.value) } }
                .onFailure { error = repo.explain(it) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (form == null) loadForm() }

    RouteScreen(route, "Paper seeing", "See your answer paper, or ask for a re-evaluation. VTOP raises a fee for it, paid on VTOP.") {
        reply?.let { ReplyCard(it) { reply = null } }
        error?.let { ErrorNote(it) { if (form == null) loadForm() } }
        val f = form?.form
        if (f == null) {
            if (loading) Loading("Reading VTOP's paper seeing form") else form?.let { InkText(it.message.ifBlank { "VTOP didn't show the paper seeing form." }) }
            return@RouteScreen
        }
        val semester = values["semesterSubId"].orEmpty()
        val service = values["serviceType"].orEmpty()
        val exam = values["examType"].orEmpty()
        InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Vtop, plateOffset = 4.dp) {
            FormFields(f.fields, values) { k, v ->
                values[k] = v
                courses = null
                chosen.clear()
                reviewing = false
            }
            Spacer(Modifier.height(14.dp))
            val ready = semester.isNotEmpty() && service.isNotEmpty() && exam.isNotEmpty()
            InkButton(if (loading) "Asking VTOP" else "Show courses", {
                if (loading) return@InkButton
                loading = true
                error = null
                scope.launch {
                    runCatching { repo.onVtop { c -> c.paperSeeingCourses(semester, service, exam) } }
                        .onSuccess { courses = it; chosen.clear() }
                        .onFailure { error = repo.explain(it) }
                    loading = false
                }
            }, enabled = ready && !loading, fillWidth = true, modifier = Modifier.fillMaxWidth())
            if (!ready) {
                Spacer(Modifier.height(6.dp))
                InkText(firstProblem(f.fields.map { it.copy(required = true) }, values) ?: "", style = RisoType.small)
            }
        }
        val page = courses ?: return@RouteScreen
        GroupLabel("Courses")
        if (page.courses.isEmpty()) {
            InkText(page.message.ifBlank { "VTOP offers no courses for this." })
            return@RouteScreen
        }
        androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            page.courses.forEach { c ->
                val on = c.value in chosen
                InkCard(Modifier.fillMaxWidth(), onClick = { if (on) chosen.remove(c.value) else chosen.add(c.value); reviewing = false }, kind = dev.medriid.davit.design.Kind.Exams, plateOffset = 3.dp) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            InkText(c.title.ifBlank { c.code.ifBlank { c.value } }, style = RisoType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 2)
                            InkText(c.details.filter { (h, v) -> v != c.title && !h.contains("Title", true) }.joinToString(" · ") { (h, v) -> if (h.contains("Code", true)) v else "${h.removePrefix("Course ")} $v" }, style = RisoType.small, maxLines = 3)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                        dev.medriid.davit.design.InkChip(if (on) "Picked" else "Pick", solid = on)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (!reviewing) {
            InkButton("Review", { reviewing = true; haptics.tick() }, enabled = chosen.isNotEmpty(), fillWidth = true, modifier = Modifier.fillMaxWidth())
        }
        AnimatedVisibility(reviewing && chosen.isNotEmpty()) {
            val fieldOf = { name: String -> f.fields.first { it.name == name } }
            SendReview(
                "Send this request to VTOP?",
                listOf(
                    "Service" to fieldOf("serviceType").shown(service),
                    "Semester" to fieldOf("semesterSubId").shown(semester),
                    "Exam" to fieldOf("examType").shown(exam),
                    "Courses" to page.courses.filter { it.value in chosen }.joinToString(", ") { it.code.ifBlank { it.title } },
                ),
                "Send request",
                sending,
                warning = "VTOP raises a fee invoice for this when it's sent. Pay it on VTOP; daVIT never pays anything.",
                onSend = {
                    sending = true
                    haptics.heavy()
                    val picked = chosen.toSet()
                    scope.launch {
                        runCatching { repo.onVtop { c -> c.submitPaperSeeing(UserConsent("paper seeing"), semester, service, exam, picked) } }
                            .onSuccess { reply = it; if (it.ok) { haptics.confirm(); courses = null; chosen.clear() } else haptics.reject(); reviewing = false }
                            .onFailure { haptics.reject(); reply = Reply(false, repo.explain(it)) }
                        sending = false
                    }
                },
                onEdit = { reviewing = false },
            )
        }
    }
}
