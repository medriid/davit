package dev.medriid.davit.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import dev.medriid.davit.sync.BackgroundSync
import dev.medriid.davit.sync.Notifier
import dev.medriid.davit.widget.DavitWidget
import dev.medriid.davit.vtop.BadCredentials
import dev.medriid.davit.vtop.CaptchaFailed
import dev.medriid.davit.vtop.ImageDecoder
import dev.medriid.davit.vtop.Offline
import dev.medriid.davit.vtop.Pixels
import dev.medriid.davit.vtop.RecaptchaRequired
import dev.medriid.davit.vtop.SessionExpired
import dev.medriid.davit.vtop.UnexpectedPage
import dev.medriid.davit.vtop.VtopClient
import dev.medriid.davit.vtop.VtopException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class UiState(
    val signedIn: Boolean = false,
    val regNo: String? = null,
    val snapshot: Snapshot? = null,
    /** What's happening right now ("Solving captcha · try 2"), or null when idle. */
    val busy: String? = null,
    val error: String? = null,
    /** Stage → ms of the last sync, for the timing readout. */
    val timings: List<Pair<String, Long>> = emptyList(),
    val inkIndex: Int = 0,
    val dumpPages: Boolean = false,
    /** The profile mascot, as [dev.medriid.davit.ui.Mascot.encode] writes it. */
    val mascot: String = "",
    /** Sync every few hours while the app is closed. */
    val background: Boolean = true,
    /** Post notifications from background syncs. */
    val alerts: Boolean = true,
    /** "7:51 pm · ok" for the last background run, or null if there hasn't been one. */
    val lastBackground: String? = null,
)

/**
 * The app's single source of truth. Credentials, the VTOP session and the data cache are
 * all sealed with [Vault] before they touch storage; nothing leaves the phone except
 * requests to vtop.vit.ac.in.
 */
class Repo(private val context: Context) {
    private val prefs = context.getSharedPreferences("davit", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val client = VtopClient(BitmapDecoder)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        val creds = credentials()
        _state.value = UiState(
            signedIn = creds != null,
            regNo = creds?.first,
            snapshot = prefs.getString(K_CACHE, null)?.let(Vault::decrypt)?.let(Snapshot::fromJson),
            inkIndex = prefs.getInt(K_INK, 0),
            dumpPages = prefs.getBoolean(K_DUMP, false),
            mascot = prefs.getString(K_MASCOT, "").orEmpty(),
            background = prefs.getBoolean(K_BACKGROUND, true),
            alerts = prefs.getBoolean(K_ALERTS, true),
            lastBackground = prefs.getString(K_LAST_BG, null),
        )
        prefs.getString(K_SESSION, null)?.let(Vault::decrypt)?.let(client::restoreSession)
    }

    /** Sync on open when the data is more than 15 minutes old. */
    fun onAppOpen() {
        val s = _state.value
        if (!s.signedIn || s.busy != null) return
        val age = System.currentTimeMillis() - (s.snapshot?.syncedAt ?: 0)
        if (age > 15 * 60_000) sync()
    }

    fun signIn(regNo: String, password: String) {
        if (_state.value.busy != null) return
        scope.launch {
            lock.withLock {
                _state.update { it.copy(busy = "Connecting to VTOP", error = null) }
                try {
                    client.login(regNo, password, onProgress = ::showLoginProgress)
                    prefs.edit()
                        .putString(K_CREDS, Vault.encrypt(regNo.trim().uppercase() + "\n" + password))
                        .apply()
                    saveSession()
                    _state.update { it.copy(signedIn = true, regNo = regNo.trim().uppercase()) }
                    BackgroundSync.apply(context, _state.value.background)
                    syncLocked()
                } catch (e: Exception) {
                    _state.update { it.copy(busy = null, error = message(e)) }
                }
            }
        }
    }

    fun sync() {
        if (!_state.value.signedIn) return
        scope.launch { lock.withLock { syncLocked() } }
    }

    fun chooseSemester(id: String) {
        prefs.edit().putString(K_SEMESTER, id).apply()
        sync()
    }

    fun setInk(index: Int) {
        prefs.edit().putInt(K_INK, index).apply()
        _state.update { it.copy(inkIndex = index) }
        DavitWidget.refresh(context)
    }

    fun setMascot(code: String) {
        prefs.edit().putString(K_MASCOT, code).apply()
        _state.update { it.copy(mascot = code) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun setBackground(on: Boolean) {
        prefs.edit().putBoolean(K_BACKGROUND, on).apply()
        _state.update { it.copy(background = on) }
        BackgroundSync.apply(context, on && _state.value.signedIn)
    }

    fun setAlerts(on: Boolean) {
        prefs.edit().putBoolean(K_ALERTS, on).apply()
        _state.update { it.copy(alerts = on) }
    }

    /** Whether the app has already asked for notification permission (it asks once). */
    var askedForNotifications: Boolean
        get() = prefs.getBoolean(K_ASKED, false)
        set(v) = prefs.edit().putBoolean(K_ASKED, v).apply()

    /**
     * A sync for the background worker: waits for any sync already running, then runs one.
     * Returns the snapshot before it, the one after, and the failure if there was one.
     */
    suspend fun syncForBackground(): Triple<Snapshot?, Snapshot?, Exception?> = lock.withLock {
        if (!_state.value.signedIn) return@withLock Triple(null, null, null)
        val before = _state.value.snapshot
        val error = syncLocked()
        val time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a", java.util.Locale.ENGLISH))
        val result = "$time · " + if (error == null) "ok" else message(error).substringBefore('.').lowercase()
        prefs.edit().putString(K_LAST_BG, result).apply()
        _state.update { it.copy(lastBackground = result) }
        Triple(before, _state.value.snapshot, error)
    }

    fun setDumpPages(on: Boolean) {
        prefs.edit().putBoolean(K_DUMP, on).apply()
        if (!on) dumpDir().deleteRecursively()
        _state.update { it.copy(dumpPages = on) }
    }

    /**
     * Logs out of VTOP, then forgets everything: credentials, session, cache, dumps, key.
     * Only the look survives (ink pair and mascot); neither says anything about you.
     */
    fun signOut() {
        scope.launch {
            lock.withLock {
                runCatching { client.logout() }
                client.clearSession()
                val ink = prefs.getInt(K_INK, 0)
                val mascot = prefs.getString(K_MASCOT, "").orEmpty()
                prefs.edit().clear().putInt(K_INK, ink).putString(K_MASCOT, mascot).apply()
                Notifier.forget(context)
                dumpDir().deleteRecursively()
                Vault.deleteKey()
                BackgroundSync.apply(context, false)
                _state.value = UiState(inkIndex = ink, mascot = mascot)
                DavitWidget.refresh(context)
            }
        }
    }

    // ---- sync ----------------------------------------------------------------------

    /** Returns the failure, or null when the sync went through. */
    private suspend fun syncLocked(): Exception? {
        val creds = credentials() ?: return null
        client.timings.clear()
        _state.update { it.copy(busy = "Syncing", error = null) }
        try {
            var semesters = withSession(creds) {
                _state.update { it.copy(busy = "Reading semesters") }
                client.semesters()
            }
            if (semesters.isEmpty()) {
                // A half-dead session (e.g. one cut off mid-request when the phone froze the app)
                // can get a page without the list instead of a login page. Start fresh once.
                Log.i(TAG, "no semesters on the saved session; signing in again")
                client.login(creds.first, creds.second, onProgress = ::showLoginProgress)
                saveSession()
                semesters = client.semesters()
            }
            if (semesters.isEmpty()) throw UnexpectedPage("VTOP didn't list any semesters.")
            val wanted = prefs.getString(K_SEMESTER, null)
            val semester = semesters.firstOrNull { it.id == wanted } ?: semesters.first()
            val old = _state.value.snapshot?.takeIf { it.semester.id == semester.id }

            // Attendance first, and shown as soon as it lands.
            _state.update { it.copy(busy = "Fetching attendance") }
            val attendance = withSession(creds) { client.attendance(semester.id) }
            dump("attendance", creds.first)
            val partial = Snapshot(
                semester, semesters, attendance, old?.timetable.orEmpty(),
                System.currentTimeMillis(),
                Snapshot.diff(old, attendance).ifEmpty { old?.changes.orEmpty() },
                marks = old?.marks.orEmpty(),
                exams = old?.exams.orEmpty(),
                grades = _state.value.snapshot?.grades,
                assignments = old?.assignments.orEmpty(),
            )
            _state.update { it.copy(snapshot = partial, busy = "Fetching timetable") }

            val timetable = withSession(creds) { client.timetable(semester.id) }
            dump("timetable", creds.first)
            var full = partial.copy(timetable = timetable)
            _state.update { it.copy(snapshot = full) }

            // Academics: each section on its own, so one odd page keeps its old data
            // instead of failing the sync. Session expiry still re-logs in (withSession).
            full = section("Fetching marks", "marks", creds, full) { client.marks(semester.id) }
                ?.let { full.copy(marks = it) } ?: full.copy(marks = old?.marks.orEmpty())
            full = section("Fetching exams", "exams", creds, full) { client.exams(semester.id) }
                ?.let { full.copy(exams = it) } ?: full.copy(exams = old?.exams.orEmpty())
            full = section("Fetching assignments", "assignments", creds, full) { client.assignments(semester.id) }
                ?.let { full.copy(assignments = it) } ?: full.copy(assignments = old?.assignments.orEmpty())
            // Grade history spans semesters, so keep it even when the semester changed.
            full = section("Fetching grades", "grades", creds, full) { client.gradeHistory() }
                ?.let { full.copy(grades = it) } ?: full.copy(grades = _state.value.snapshot?.grades)
            // Since you last looked: new marks first, then attendance moves; else keep the last list.
            val changes = Snapshot.marksDiff(old?.marks, full.marks) + Snapshot.diff(old, attendance)
            full = full.copy(syncedAt = System.currentTimeMillis(), changes = changes.ifEmpty { old?.changes.orEmpty() })
            prefs.edit().putString(K_CACHE, Vault.encrypt(full.toJson())).apply()
            saveSession()
            Log.i(TAG, "sync ok: " + client.timings.joinToString { "${it.first}=${it.second}ms" })
            _state.update { it.copy(snapshot = full, busy = null, timings = client.timings.toList()) }
            DavitWidget.refresh(context)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.javaClass.simpleName}")
            _state.update { it.copy(busy = null, error = message(e), timings = client.timings.toList()) }
            return e
        }
    }

    /** One optional section of the sync: shows [label], returns null if it failed for any reason but auth. */
    private suspend fun <T> section(
        label: String,
        dumpName: String,
        creds: Pair<String, String>,
        shown: Snapshot,
        block: () -> T,
    ): T? {
        _state.update { it.copy(snapshot = shown, busy = label) }
        return try {
            withSession(creds, block).also { dump(dumpName, creds.first) }
        } catch (e: BadCredentials) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$dumpName failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Runs [block] on the saved session; if VTOP says it expired, signs in again once. */
    private suspend fun <T> withSession(creds: Pair<String, String>, block: () -> T): T {
        if (client.session != null) {
            try {
                return block()
            } catch (_: SessionExpired) {
            }
        }
        client.login(creds.first, creds.second, onProgress = ::showLoginProgress)
        saveSession()
        return block()
    }

    private fun showLoginProgress(p: VtopClient.Progress) {
        val text = when (p) {
            VtopClient.Progress.Connecting -> "Connecting to VTOP"
            is VtopClient.Progress.Captcha -> if (p.attempt == 1) "Solving captcha" else "Solving captcha · try ${p.attempt}"
            VtopClient.Progress.Verifying -> "Signing in"
            is VtopClient.Progress.Dodging -> "Google captcha, retrying · ${p.attempt}/${p.of}"
        }
        _state.update { it.copy(busy = text) }
    }

    private fun saveSession() {
        client.exportSession()?.let { prefs.edit().putString(K_SESSION, Vault.encrypt(it)).apply() }
    }

    private fun credentials(): Pair<String, String>? {
        val plain = prefs.getString(K_CREDS, null)?.let(Vault::decrypt) ?: return null
        val i = plain.indexOf('\n')
        return if (i <= 0) null else plain.substring(0, i) to plain.substring(i + 1)
    }

    // ---- debug dumps ---------------------------------------------------------------

    private fun dumpDir() = File(context.filesDir, "vtop-pages")

    /**
     * Opt-in (Account → "Save VTOP pages"): keeps the last raw page of each kind so a
     * parser can be fixed against the real markup. Username and CSRF tokens are
     * replaced before writing; the page may still show your name and courses.
     */
    private fun dump(name: String, regNo: String) {
        if (!_state.value.dumpPages) return
        val html = client.lastPage?.second ?: return
        val scrubbed = html
            .replace(Regex(Regex.escape(regNo), RegexOption.IGNORE_CASE), "REGNO")
            .replace(Regex("""<input[^>]*name=["']_csrf["'][^>]*>""", RegexOption.IGNORE_CASE), """<input type="hidden" name="_csrf" value="CSRF">""")
        dumpDir().mkdirs()
        File(dumpDir(), "$name.html").writeText(scrubbed)
    }

    private fun message(e: Exception): String = when (e) {
        is BadCredentials -> "VTOP said the username or password is wrong."
        is RecaptchaRequired -> "VTOP kept asking for Google's \"I'm not a robot\" check instead of its usual " +
            "letter captcha, even after several retries. Only a person can tick that box; try again in a few minutes."
        is CaptchaFailed -> "Couldn't read VTOP's captcha after several tries. Try again."
        is Offline -> "Couldn't reach VTOP. Check your connection."
        is VtopException -> e.message ?: "VTOP returned something unexpected."
        else -> "Something went wrong (${e.javaClass.simpleName})."
    }

    companion object {
        private const val TAG = "daVIT"
        private const val K_CREDS = "creds"
        private const val K_SESSION = "session"
        private const val K_CACHE = "cache"
        private const val K_SEMESTER = "semester"
        private const val K_INK = "ink"
        private const val K_DUMP = "dump"
        private const val K_MASCOT = "mascot"
        private const val K_BACKGROUND = "background"
        private const val K_ALERTS = "alerts"
        private const val K_ASKED = "askedNotifications"
        private const val K_LAST_BG = "lastBackground"
    }
}

private object BitmapDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): Pixels? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Pixels(bmp.width, bmp.height, px).also { bmp.recycle() }
    }
}
