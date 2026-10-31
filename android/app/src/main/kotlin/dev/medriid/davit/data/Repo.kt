package dev.medriid.davit.data

import dev.medriid.davit.design.InkPairs
import androidx.compose.ui.graphics.toArgb
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
import kotlinx.coroutines.withContext
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
    /** Your colours: an ink pair and whatever you changed on top of it. */
    val palette: Palette = Palette(),
    val dumpPages: Boolean = false,
    /** The profile mascot, as [dev.medriid.davit.ui.Mascot.encode] writes it. */
    val mascot: String = "",
    /** Sync every few hours while the app is closed. */
    val background: Boolean = true,
    /** Post notifications from background syncs. */
    val alerts: Boolean = true,
    /** "7:51 pm · ok" for the last background run, or null if there hasn't been one. */
    val lastBackground: String? = null,
    val reminders: List<Reminder> = emptyList(),
    /** 0 follows the system, 1 paper, 2 night print. */
    val theme: Int = 0,
    /** Ask for fingerprint, face or screen lock when opening the app. */
    val appLock: Boolean = false,
    /** What to notify about, and when (thresholds, muted courses, before-class reminders). */
    val alertPrefs: AlertPrefs = AlertPrefs(),
    /** The display card: the top bar on Home. */
    val banner: Banner = Banner(),
    /** When your own background photo last changed, so the card reloads it. */
    val bannerPhotoAt: Long = 0,
    /** Your names for courses, by course code. Already applied to [snapshot] as [Repo.state] gives it. */
    val aliases: Map<String, String> = emptyMap(),
    /** Days VTOP has you absent for that you say you attended. */
    val corrections: List<Correction> = emptyList(),
    /** Friends' weeks, from the codes they shared. */
    val friends: List<Friend> = emptyList(),
    /** The name your shared week carries. */
    val shareName: String = "",
    /** Next semester's timetable plan: the semester it's for and the classes picked. */
    val plan: Pair<String, List<PlanPick>> = "" to emptyList(),
)

/**
 * The app's single source of truth. Credentials, the VTOP session and the data cache are
 * all sealed with [Vault] before they touch storage; nothing leaves the phone except
 * requests to vtop.vit.ac.in.
 */
class Repo(private val context: Context) {
    private val prefs = context.getSharedPreferences("davit", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val client = VtopClient(BitmapDecoder, netLog = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) { s -> android.util.Log.i("daVIT-net", s) } else null)

    private val _state = MutableStateFlow(UiState())

    /**
     * What everything outside reads: labs counted in sessions and your course names in place of VTOP's.
     * Inside, [_state] keeps VTOP's own, which is what gets saved.
     */
    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    val state: StateFlow<UiState> = object : StateFlow<UiState> {
        // Worked out on read, never lagging behind: the app reads this the moment it starts.
        override val value: UiState get() = _state.value.withNames()
        override val replayCache: List<UiState> get() = listOf(value)
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<UiState>): Nothing =
            _state.collect { collector.emit(it.withNames()) }
    }

    // The last state worked out, and what it became: reads between changes cost nothing.
    @Volatile private var shown: Pair<UiState, UiState>? = null

    /** Labs in sessions where VTOP counted both periods, then your names for courses. */
    private fun UiState.withNames(): UiState {
        shown?.let { (from, to) -> if (from === this) return to }
        val out = copy(snapshot = snapshot?.withLabSessions()?.let { if (aliases.isEmpty()) it else it.withAliases(aliases) })
        shown = this to out
        return out
    }

    /** Downloaded files, kept for offline. */
    val files by lazy { FileStore(context) }

    /** Records fetched when their screen opens: proctor, messages, receipts, curriculum… */
    val campus by lazy { CampusStore(context, this) }

    val materials by lazy { MaterialsStore(context, this, files) }

    /** Assignment uploads in progress, and their OTP step. */
    val uploads by lazy { Uploads(this) }

    /** The document scanner's session (photos to PDF). */
    val scanner by lazy { dev.medriid.davit.scan.Scanner(context, scope) }

    /** The campus map: buildings, paths, places and PRP rooms, cached on the phone. */
    val maps by lazy { MapStore(context, scope) }

    /** A venue another screen asked the map to show ("SJT-401", a cabin); the map clears it. */
    val mapFocus = MutableStateFlow<String?>(null)

    /** Asked for from elsewhere (a Home shortcut): which Academics section to open (0 marks … 5 faculty). */
    val academicsFocus = MutableStateFlow<Int?>(null)

    init {
        val creds = credentials()
        _state.value = UiState(
            signedIn = creds != null,
            regNo = creds?.first,
            snapshot = prefs.getString(K_CACHE, null)?.let(Vault::decrypt)?.let(Snapshot::fromJson),
            palette = Palette.fromJson(prefs.getString(K_PALETTE, null)) ?: Palette(base = prefs.getInt(K_INK, 0)),
            dumpPages = prefs.getBoolean(K_DUMP, false),
            mascot = prefs.getString(K_MASCOT, "").orEmpty(),
            background = prefs.getBoolean(K_BACKGROUND, true),
            alerts = prefs.getBoolean(K_ALERTS, true),
            lastBackground = prefs.getString(K_LAST_BG, null),
            reminders = prefs.getString(K_REMINDERS, null)?.let(Vault::decrypt)?.let(::remindersFromJson).orEmpty(),
            theme = prefs.getInt(K_THEME, 0),
            appLock = prefs.getBoolean(K_LOCK, false),
            alertPrefs = AlertPrefs.fromJson(prefs.getString(K_ALERT_PREFS, null)),
            banner = Banner.fromJson(prefs.getString(K_BANNER, null)),
            bannerPhotoAt = bannerPhoto.lastModified(),
            aliases = aliasesFromJson(prefs.getString(K_ALIASES, null)),
            corrections = correctionsFromJson(prefs.getString(K_CORRECTIONS, null)?.let(Vault::decrypt)),
            friends = friendsFromJson(prefs.getString(K_FRIENDS, null)?.let(Vault::decrypt)),
            shareName = prefs.getString(K_SHARE_NAME, null)?.let(Vault::decrypt).orEmpty(),
            plan = planFromJson(prefs.getString(K_PLAN, null)?.let(Vault::decrypt)),
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

    /** Starts over from ink pair [index], dropping any colours you changed. */
    fun setInk(index: Int) = setPalette(Palette(base = index, tinted = _state.value.palette.tinted))

    /**
     * Classic (the print looks, in the colours you chose) or Editorial. Your Classic colours
     * are kept while you try Editorial, and come back when you switch back.
     */
    fun setEditorial(on: Boolean) {
        val now = _state.value.palette
        if (on == InkPairs.of(now.base, false).editorial) return
        if (on) {
            prefs.edit().putString(K_CLASSIC, now.toJson()).apply()
            setPalette(Palette(base = InkPairs.editorialIndex, tinted = now.tinted))
        } else {
            setPalette(Palette.fromJson(prefs.getString(K_CLASSIC, null)) ?: Palette(base = 0, tinted = now.tinted))
        }
    }

    /** Shows [p] everywhere while you drag the colour wheel, without saving it; [setPalette] keeps it. */
    fun previewPalette(p: Palette) = _state.update { it.copy(palette = p) }

    fun setPalette(p: Palette) {
        prefs.edit().putString(K_PALETTE, p.toJson()).putInt(K_INK, p.base).apply()
        _state.update { it.copy(palette = p) }
        DavitWidget.refresh(context)
    }

    fun setAppLock(on: Boolean) {
        prefs.edit().putBoolean(K_LOCK, on).apply()
        _state.update { it.copy(appLock = on) }
    }

    fun setTheme(theme: Int) {
        prefs.edit().putInt(K_THEME, theme).apply()
        _state.update { it.copy(theme = theme) }
        DavitWidget.refresh(context)
    }

    /** Debug builds' demo seed only (see src/debug): shows [snapshot] as if signed in, with no account. */
    internal fun showDemo(snapshot: Snapshot) {
        _state.update { it.copy(signedIn = true, regNo = "DEMO", snapshot = snapshot) }
    }

    /** Debug builds' demo only: a fake VTOP on the phone itself that [onVtop] uses instead (src/debug). */
    @Volatile
    internal var demoClient: VtopClient? = null

    fun setMascot(code: String) {
        prefs.edit().putString(K_MASCOT, code).apply()
        _state.update { it.copy(mascot = code) }
    }

    /** Your own display card background, kept on this phone only (like the mascot, it stays when you sign out). */
    val bannerPhoto: File get() = File(context.filesDir, "look/banner.jpg")

    /** Every course this semester by code, with VTOP's own name (not yours): for the names screen. */
    fun vtopCourses(): List<Pair<String, String>> = _state.value.snapshot?.coursesForAliases().orEmpty()

    /** Your name for the course [code]; blank brings back VTOP's. */
    fun setAlias(code: String, name: String) {
        val next = (_state.value.aliases + (code to name.trim())).filterValues { it.isNotBlank() }
        prefs.edit().putString(K_ALIASES, aliasesToJson(next)).apply()
        _state.update { it.copy(aliases = next) }
        DavitWidget.refresh(context)
    }

    fun setBanner(b: Banner) {
        prefs.edit().putString(K_BANNER, b.toJson()).apply()
        _state.update { it.copy(banner = b) }
    }

    /** Takes the picked image as the display card's background: scaled to at most 1600 px and saved as a JPEG. */
    suspend fun setBannerPhoto(uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            val raw = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching false
            val scaled = dev.medriid.davit.scan.Render.fit(raw, 1600)
            bannerPhoto.parentFile?.mkdirs()
            bannerPhoto.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
            scaled.recycle()
            true
        }.getOrDefault(false)
        if (ok) {
            setBanner(_state.value.banner.copy(photo = true, focus = 0.5f))
            _state.update { it.copy(bannerPhotoAt = bannerPhoto.lastModified()) }
        }
        ok
    }

    fun removeBannerPhoto() {
        bannerPhoto.delete()
        setBanner(_state.value.banner.copy(photo = false))
        _state.update { it.copy(bannerPhotoAt = 0) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Runs [block] for something you opened or tapped (a download, a course page, an
     * upload): after any sync in progress, on the saved session, signing in again once if
     * it expired (unless [retry] is false, for steps that can't be repeated on a new session).
     */
    suspend fun <T> onVtop(retry: Boolean = true, block: (VtopClient) -> T): T = withContext(Dispatchers.IO) {
        demoClient?.let { c -> return@withContext lock.withLock { block(c) } }
        lock.withLock {
            val creds = credentials() ?: throw SessionExpired()
            val wasBusy = _state.value.busy
            try {
                (if (retry) withSession(creds) { block(client) } else block(client)).also { saveSession() }
            } finally {
                // A sign-in on the way shows its steps up top; put the bar back as it was.
                if (wasBusy == null) _state.update { it.copy(busy = null) }
            }
        }
    }

    /** The home page feeds now (pull to refresh on events, say), not waiting for the daily turn. */
    suspend fun refreshFeeds() {
        val now = System.currentTimeMillis()
        val f = onVtop { c ->
            HomeFeeds(
                remarks = c.remarks(), remarksAt = now,
                feedback = c.feedbackStatus(), events = c.events(), proctorNote = c.proctorNote(),
                registration = c.registrationSchedule(), dailyAt = now,
            )
        }
        updateSnapshot { it.copy(feeds = f.copy(classGroups = it.feeds.classGroups)) }
        scheduleRegistrationReminders(f)
    }

    /** Night slips and leave, now (after applying, say). */
    suspend fun refreshHostel() {
        val h = onVtop { c -> c.hostelRequests() }
        updateSnapshot { it.copy(hostel = h, hostelAt = System.currentTimeMillis()) }
    }

    private fun updateSnapshot(change: (Snapshot) -> Snapshot) {
        _state.update { s -> s.copy(snapshot = s.snapshot?.let(change)) }
        _state.value.snapshot?.let { prefs.edit().putString(K_CACHE, Vault.encrypt(it.toJson())).apply() }
        DavitWidget.refresh(context)
    }

    /** What to tell you about a failure, in plain words. */
    fun explain(e: Throwable): String = if (e is Exception) message(e) else "Something went wrong."

    /** One course's assignments after an upload or a cancelled one. */
    fun updateAssignments(classId: String, items: List<dev.medriid.davit.vtop.Assignment>) {
        if (items.isEmpty()) return
        _state.update { s ->
            val snap = s.snapshot ?: return@update s
            s.copy(snapshot = snap.copy(assignments = snap.assignments.map { if (it.classId == classId) it.copy(items = items) else it }))
        }
        _state.value.snapshot?.let { prefs.edit().putString(K_CACHE, Vault.encrypt(it.toJson())).apply() }
        DavitWidget.refresh(context)
    }

    /** The calendar's class group: [id] by hand, or null to go back to detecting it from attendance. */
    fun setCalendarGroup(id: String?) {
        val sem = _state.value.snapshot?.semester?.id ?: return
        prefs.edit().apply {
            if (id == null) remove(K_CAL_MANUAL + sem) else putString(K_CAL_MANUAL + sem, id)
            remove(K_CAL_GROUP + sem)
        }.apply()
        _state.update { s -> s.copy(snapshot = s.snapshot?.copy(calendarAt = 0)) }
        sync()
    }

    /** The group chosen by hand for this semester, or null when it's detected. */
    fun manualCalendarGroup(): String? = _state.value.snapshot?.semester?.id?.let { prefs.getString(K_CAL_MANUAL + it, null) }

    fun addReminder(title: String, date: java.time.LocalDate) =
        saveReminders(_state.value.reminders + Reminder(System.currentTimeMillis(), title.trim(), date))

    fun removeReminder(id: Long) = saveReminders(_state.value.reminders.filterNot { it.id == id })

    /** Marks a day VTOP has you absent for as attended (or on duty); again with the same day unmarks it. */
    fun toggleCorrection(course: String, date: String, slot: String, onDuty: Boolean) {
        val now = _state.value.corrections
        val id = "$course|$date|$slot"
        val next = if (now.any { it.id == id }) now.filterNot { it.id == id } else now + Correction(course, date, slot, onDuty, System.currentTimeMillis())
        prefs.edit().putString(K_CORRECTIONS, Vault.encrypt(correctionsToJson(next))).apply()
        _state.update { it.copy(corrections = next) }
    }

    /** Adds the week in [code] (a QR code or a pasted message); false if there's no week in it. */
    fun addFriend(code: String): Boolean {
        val (name, classes) = WeekCode.decode(code) ?: return false
        val now = _state.value.friends
        // The same name again replaces their old week: a new semester, a changed class.
        val next = now.filterNot { it.name.equals(name, ignoreCase = true) } + Friend(System.currentTimeMillis(), name, classes)
        saveFriends(next)
        return true
    }

    fun removeFriend(id: Long) = saveFriends(_state.value.friends.filterNot { it.id == id })

    private fun saveFriends(list: List<Friend>) {
        prefs.edit().putString(K_FRIENDS, Vault.encrypt(friendsToJson(list))).apply()
        _state.update { it.copy(friends = list) }
    }

    fun setShareName(name: String) {
        prefs.edit().putString(K_SHARE_NAME, Vault.encrypt(name)).apply()
        _state.update { it.copy(shareName = name) }
    }

    fun setPlan(semester: String, picks: List<PlanPick>) {
        prefs.edit().putString(K_PLAN, Vault.encrypt(planToJson(semester, picks))).apply()
        _state.update { it.copy(plan = semester to picks) }
    }

    private fun saveReminders(list: List<Reminder>) {
        val sorted = list.sortedBy { it.date }
        prefs.edit().putString(K_REMINDERS, Vault.encrypt(sorted.toJson())).apply()
        _state.update { it.copy(reminders = sorted) }
        DavitWidget.refresh(context)
    }

    fun setBackground(on: Boolean) {
        prefs.edit().putBoolean(K_BACKGROUND, on).apply()
        _state.update { it.copy(background = on) }
        BackgroundSync.apply(context, on && _state.value.signedIn)
    }

    fun setAlertPrefs(p: AlertPrefs) {
        prefs.edit().putString(K_ALERT_PREFS, p.toJson()).apply()
        _state.update { it.copy(alertPrefs = p) }
    }

    fun setAlerts(on: Boolean) {
        prefs.edit().putBoolean(K_ALERTS, on).apply()
        _state.update { it.copy(alerts = on) }
    }

    /** Whether the app has already asked for notification permission (it asks once). */
    var askedForNotifications: Boolean
        get() = prefs.getBoolean(K_ASKED, false)
        set(v) = prefs.edit().putBoolean(K_ASKED, v).apply()

    /** The welcome page (look and mascot) was seen once; after that, signing in goes straight to the form. */
    var welcomed: Boolean
        get() = prefs.getBoolean(K_WELCOMED, false)
        set(v) = prefs.edit().putBoolean(K_WELCOMED, v).apply()

    /** The setup after the first sync is done. Anyone who used daVIT before it existed counts as done. */
    var setupDone: Boolean
        get() = prefs.getBoolean(K_SETUP, askedForNotifications)
        set(v) = prefs.edit().putBoolean(K_SETUP, v).apply()

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
                val palette = prefs.getString(K_PALETTE, null)
                val mascot = prefs.getString(K_MASCOT, "").orEmpty()
                val theme = prefs.getInt(K_THEME, 0)
                val banner = prefs.getString(K_BANNER, null)
                val classic = prefs.getString(K_CLASSIC, null)
                prefs.edit().clear().putInt(K_INK, ink).putString(K_PALETTE, palette).putString(K_MASCOT, mascot).putInt(K_THEME, theme).putString(K_BANNER, banner)
                    .putString(K_CLASSIC, classic)
                    .putBoolean(K_WELCOMED, true).apply()
                Notifier.forget(context)
                PhoneCalendar.turnOff(context)
                dev.medriid.davit.sync.Reminders.cancelAll(context)
                dumpDir().deleteRecursively()
                files.wipe()
                campus.wipe()
                materials.wipe()
                scanner.close()
                Vault.deleteKey()
                BackgroundSync.apply(context, false)
                _state.value = UiState(palette = Palette.fromJson(palette) ?: Palette(base = ink), mascot = mascot, theme = theme, banner = Banner.fromJson(banner), bannerPhotoAt = bannerPhoto.lastModified())
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
            val wanted = prefs.getString(K_SEMESTER, null) ?: currentSemesterId()
            var semester = semesters.firstOrNull { it.id == wanted }
            var found: List<dev.medriid.davit.vtop.AttendanceRow>? = null
            _state.update { it.copy(busy = "Fetching attendance") }
            if (semester == null) {
                // Your current semester: the newest VTOP has classes for. A semester can be
                // listed before it starts, with no attendance yet. Remembered for a day.
                for (s in semesters.take(3)) {
                    val rows = withSession(creds) { client.attendance(s.id) }
                    if (rows.isNotEmpty()) {
                        semester = s
                        found = rows
                        break
                    }
                }
                semester = semester ?: semesters.first()
                prefs.edit().putString(K_CUR_SEM, semester.id).putLong(K_CUR_SEM_AT, System.currentTimeMillis()).apply()
            }
            val old = _state.value.snapshot?.takeIf { it.semester.id == semester.id }

            // Attendance first, and shown as soon as it lands.
            val attendance = found ?: withSession(creds) { client.attendance(semester.id) }
            dump("attendance", creds.first)
            val partial = Snapshot(
                semester, semesters, attendance, old?.timetable.orEmpty(),
                System.currentTimeMillis(),
                Snapshot.diff(old, attendance).ifEmpty { old?.changes.orEmpty() },
                marks = old?.marks.orEmpty(),
                exams = old?.exams.orEmpty(),
                grades = _state.value.snapshot?.grades,
                assignments = old?.assignments.orEmpty(),
                history = old?.history.orEmpty(),
                profile = _state.value.snapshot?.profile,
                profileAt = _state.value.snapshot?.profileAt ?: 0,
                hostel = _state.value.snapshot?.hostel,
                hostelAt = _state.value.snapshot?.hostelAt ?: 0,
                calendar = old?.calendar,
                calendarAt = old?.calendarAt ?: 0,
                faculty = old?.faculty.orEmpty(),
                facultyAt = old?.facultyAt ?: 0,
                feeds = _state.value.snapshot?.feeds ?: HomeFeeds(),
            )
            _state.update { it.copy(snapshot = partial, busy = "Fetching timetable") }

            val timetable = withSession(creds) { client.timetable(semester.id) }
            dump("timetable", creds.first)
            var full = partial.copy(timetable = timetable)
            _state.update { it.copy(snapshot = full) }

            // Day-by-day history, only for courses whose count moved (or that have none yet):
            // usually a request or two instead of one per course.
            val before = old?.attendance.orEmpty().associateBy { it.key }
            val history = old?.history.orEmpty().filterKeys { k -> attendance.any { it.key == k } }.toMutableMap()
            val stale = attendance.filter { row ->
                val prev = before[row.key]
                row.detail.usable && (row.key !in history || prev == null || prev.attended != row.attended || prev.total != row.total)
            }
            stale.forEachIndexed { i, row ->
                section("Attendance history ${i + 1}/${stale.size}", "history", creds, full) { client.attendanceDetail(row, semester.id) }
                    ?.let { history[row.key] = it }
            }
            full = full.copy(history = history)

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
            // Cool data: profile at most daily, hostel requests every few hours (hostellers only).
            val now = System.currentTimeMillis()
            if (full.profile == null || now - full.profileAt > 24 * 3_600_000L) {
                section("Fetching profile", "profile", creds, full) { client.profile() }?.let { p ->
                    full = full.copy(profile = p, profileAt = now)
                    // First time only: dress the mascot by VTOP's gender until you pick one yourself.
                    if (prefs.getString(K_MASCOT, null) == null && p.gender.equals("female", ignoreCase = true)) {
                        setMascot("1,0,0,0")
                    }
                }
            }
            if (full.profile?.hostel != null && now - full.hostelAt > 3 * 3_600_000L) {
                section("Fetching hostel requests", "hostel", creds, full) { client.hostelRequests() }
                    ?.let { full = full.copy(hostel = it, hostelAt = now) }
            }

            // VTOP's home page: attendance remarks (debarment) every sync, one request; the
            // pending feedback, club events, proctor's note and registration window about daily.
            var feeds = full.feeds
            section("Reading attendance remarks", "remarks", creds, full) { client.remarks() }
                ?.let { feeds = feeds.copy(remarks = it, remarksAt = now) }
            if (now - feeds.dailyAt > 20 * 3_600_000L) {
                val feedback = section("Reading feedback status", "feedback", creds, full) { client.feedbackStatus() }
                val events = section("Reading club events", "events", creds, full) { client.events() }
                val note = section("Reading the proctor's note", "proctor", creds, full) { listOfNotNull(client.proctorNote()) }
                val registration = section("Reading the registration schedule", "registration", creds, full) { client.registrationSchedule() }
                feeds = feeds.copy(
                    feedback = feedback ?: feeds.feedback,
                    events = events ?: feeds.events,
                    proctorNote = if (note != null) note.firstOrNull() else feeds.proctorNote,
                    registration = registration ?: feeds.registration,
                    dailyAt = if (listOf(feedback, events, note, registration).all { it != null }) now else feeds.dailyAt,
                )
            }
            full = full.copy(feeds = feeds)
            scheduleRegistrationReminders(feeds)

            // The academic calendar from VTOP, for your class group (freshers' Saturdays differ),
            // every few days; if VTOP's fails, the last one read stays.
            if (full.calendar?.semesterId != semester.id || full.calendar?.group.isNullOrEmpty() || now - full.calendarAt > 3 * 24 * 3_600_000L) {
                _state.update { it.copy(snapshot = full, busy = "Fetching calendar") }
                val cal = section("Fetching calendar", "calendar", creds, full) { vtopCalendar(semester.id, full) }
                cal?.let { full = full.copy(calendar = it, calendarAt = now) }
                if (groupsSeen.isNotEmpty()) full = full.copy(feeds = full.feeds.copy(classGroups = groupsSeen))
            }
            if (full.timetable.isNotEmpty() && (full.facultyAt == 0L || now - full.facultyAt > 7 * 24 * 3_600_000L)) {
                // Each of your teachers through VTOP's own faculty search: the hit whose name
                // matches (or the only hit), then its card.
                val names = full.timetable.map { it.faculty }.filter { facultyKey(it).length >= 3 }.distinctBy { facultyKey(it) }
                val cards = names.mapIndexedNotNull { i, name ->
                    section("Faculty ${i + 1}/${names.size}", "faculty", creds, full) {
                        val hits = client.searchFaculty(facultyKey(name))
                        val hit = hits.singleOrNull { facultyKey(it.name) == facultyKey(name) } ?: hits.singleOrNull()
                        hit?.let { client.faculty(it.empId) }
                    }
                }
                full = full.copy(faculty = cards.ifEmpty { full.faculty }, facultyAt = now)
            }

            // Since you last looked: new marks first, then attendance moves; else keep the last list.
            val changes = Snapshot.marksDiff(old?.marks, full.marks) + Snapshot.diff(old, attendance)
            full = full.copy(syncedAt = System.currentTimeMillis(), changes = changes.ifEmpty { old?.changes.orEmpty() })
            prefs.edit().putString(K_CACHE, Vault.encrypt(full.toJson())).apply()
            saveSession()
            Log.i(TAG, "sync ok: " + client.timings.joinToString { "${it.first}=${it.second}ms" })
            _state.update { it.copy(snapshot = full, busy = null, timings = client.timings.toList()) }
            DavitWidget.refresh(context)
            // Your classes in the phone's calendar, when that's on: rewritten from now with the latest timetable and calendar.
            PhoneCalendar.refresh(context, full.classEvents(java.time.LocalDate.now()), _state.value.palette.inks(false).a.toArgb())
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

    /**
     * VTOP's calendar for the student's class group. VTOP doesn't say which group you're in,
     * but your attendance does: a Saturday class listed as "THU" only fits a group whose
     * calendar has that Saturday on Thursday's order. Candidates are tried in order, fetching
     * only the months with such evidence, and the first that fits every class wins; the choice
     * is kept per semester. With no evidence yet, General is used and checked again later.
     */
    private fun vtopCalendar(semesterId: String, shown: Snapshot): AcademicCalendar? {
        val groups = client.classGroups(semesterId)
        groupsSeen = groups
        if (groups.isEmpty()) return null
        val names = groups.associate { it.id to it.name }
        // A group picked by hand in Account wins over the detected one.
        val saved = prefs.getString(K_CAL_MANUAL + semesterId, null)?.takeIf { it in names }
            ?: prefs.getString(K_CAL_GROUP + semesterId, null)?.takeIf { it in names }
        val chosen = saved ?: run {
            val evidence = shown.observedDayOrders().filter { (d, order) -> d.dayOfWeek.value != order }
            val candidates = listOf("ALL", "ALL03", "ALL04", "ALL10", "ALL02", "ALL09", "ALL06").filter { it in names }
            if (evidence.isEmpty()) return@run null
            val months = evidence.keys.map { it.format(java.time.format.DateTimeFormatter.ofPattern("MMM-yyyy", java.util.Locale.ENGLISH)).uppercase() }.toSet()
            candidates.firstOrNull { id ->
                val cal = AcademicCalendar.fromVtop(semesterId, names.getValue(id), client.calendar(semesterId, id, months))
                evidence.all { (d, order) -> cal.timetableDay(d) == order }
            }?.also { prefs.edit().putString(K_CAL_GROUP + semesterId, it).apply() }
        } ?: "ALL".takeIf { it in names } ?: groups.first().id
        val cells = client.calendar(semesterId, chosen)
        return if (cells.isEmpty()) null else AcademicCalendar.fromVtop(semesterId, names[chosen].orEmpty(), cells)
    }

    /** The class groups the last calendar fetch saw, for choosing one by hand. */
    private var groupsSeen: List<dev.medriid.davit.vtop.ClassGroup> = emptyList()

    /**
     * A local reminder 30 minutes before each registration window opens. No network:
     * WorkManager just posts the notification at that time.
     */
    private fun scheduleRegistrationReminders(feeds: HomeFeeds) {
        if (!_state.value.alerts) return
        val now = java.time.LocalDateTime.now()
        feeds.registration.forEach { w ->
            val start = w.start ?: return@forEach
            if (start.isAfter(now)) dev.medriid.davit.sync.Reminders.registration(context, w)
        }
    }

    /** The current semester found within the last day; null to look again. */
    private fun currentSemesterId(): String? {
        val at = prefs.getLong(K_CUR_SEM_AT, 0)
        return if (System.currentTimeMillis() - at < 24 * 3_600_000L) prefs.getString(K_CUR_SEM, null) else null
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

    /** App-specific external storage, so a saved page can be pulled over USB (adb) to fix a parser. */
    private fun dumpDir() = File(context.getExternalFilesDir(null) ?: context.filesDir, "vtop-pages")

    /**
     * Opt-in (Account → "Save VTOP pages"): keeps the last raw page of each kind so a
     * parser can be fixed against the real markup. Username and CSRF tokens are
     * replaced before writing; the page may still show your name and courses.
     */
    /** [dump] for pages opened outside a sync (the upload form). */
    internal fun dumpLast(name: String) = _state.value.regNo?.let { dump(name, it) }

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
        private const val K_PALETTE = "palette"
        private const val K_DUMP = "dump"
        private const val K_MASCOT = "mascot"
        private const val K_BANNER = "banner"
        private const val K_ALIASES = "aliases"
        private const val K_CLASSIC = "classic_palette"
        private const val K_CORRECTIONS = "corrections"
        private const val K_FRIENDS = "friends"
        private const val K_SHARE_NAME = "share_name"
        private const val K_PLAN = "timetable_plan"
        private const val K_BACKGROUND = "background"
        private const val K_ALERTS = "alerts"
        private const val K_ASKED = "askedNotifications"
        private const val K_WELCOMED = "welcomed"
        private const val K_SETUP = "setupDone"
        private const val K_LAST_BG = "lastBackground"
        private const val K_REMINDERS = "reminders"
        private const val K_THEME = "theme"
        private const val K_LOCK = "appLock"
        private const val K_ALERT_PREFS = "alertPrefs"
        private const val K_CAL_GROUP = "calendarGroup:"
        private const val K_CAL_MANUAL = "calendarGroupManual:"
        private const val K_CUR_SEM = "currentSemester"
        private const val K_CUR_SEM_AT = "currentSemesterAt"
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
