package dev.medriid.davit.data

import android.content.Context
import dev.medriid.davit.vtop.CourseOption
import dev.medriid.davit.vtop.CurriculumCategory
import dev.medriid.davit.vtop.CurriculumCourse
import dev.medriid.davit.vtop.FeeLetter
import dev.medriid.davit.vtop.Person
import dev.medriid.davit.vtop.Receipt
import dev.medriid.davit.vtop.SemesterGrades
import dev.medriid.davit.vtop.SimpleTable
import dev.medriid.davit.vtop.SpotlightItem
import dev.medriid.davit.vtop.VtopClient
import dev.medriid.davit.vtop.VtopMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/**
 * The "cold" parts of VTOP: fetched when you open the screen that shows them (or pull to
 * refresh there), never in background sync, and cached encrypted for offline.
 */
@Serializable
data class Campus(
    val proctor: Person? = null,
    val hodDean: List<Person> = emptyList(),
    val peopleAt: Long = 0,
    val classMessages: List<VtopMessage> = emptyList(),
    val proctorMessages: List<VtopMessage> = emptyList(),
    val messagesAt: Long = 0,
    val spotlight: List<SpotlightItem> = emptyList(),
    val spotlightAt: Long = 0,
    val gate: SimpleTable? = null,
    val gateFrom: String = "",
    val gateAt: Long = 0,
    val receipts: List<Receipt> = emptyList(),
    val feeLetters: List<FeeLetter> = emptyList(),
    val library: List<LibraryLine> = emptyList(),
    val moneyAt: Long = 0,
    val categories: List<CurriculumCategory> = emptyList(),
    val curriculum: List<CurriculumCourse> = emptyList(),
    val curriculumAt: Long = 0,
    val semesterGrades: Map<String, SemesterGrades> = emptyMap(),
    val gradesAt: Long = 0,
    /** The course-page dropdown: this semester's courses and next semester's. */
    val courses: List<CourseOption> = emptyList(),
    val coursesAt: Long = 0,
)

@Serializable
data class LibraryLine(val label: String, val amount: Double?)

enum class Section(val title: String, private val freshFor: Long) {
    People("Proctor, HOD and dean", 7 * DAY),
    Messages("Messages", 20 * MIN),
    Spotlight("Spot-light", 6 * HOUR),
    Gate("Gate log", 20 * MIN),
    Money("Receipts and dues", DAY),
    Curriculum("Curriculum", 14 * DAY),
    Grades("Semester grades", DAY),
    Courses("Course pages", DAY);

    fun fresh(at: Long) = System.currentTimeMillis() - at < freshFor
}

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

/** Loads, caches and refreshes [Campus], one [Section] at a time, on the user's say-so. */
class CampusStore(private val context: Context, private val repo: Repo) {
    private val file = File(context.filesDir, "campus.enc")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Campus> = _state

    private val _loading = MutableStateFlow<Set<Section>>(emptySet())
    val loading: StateFlow<Set<Section>> = _loading

    private val _errors = MutableStateFlow<Map<Section, String>>(emptyMap())
    val errors: StateFlow<Map<Section, String>> = _errors

    /** How far back the gate log goes: VTOP lists everything from the date it's given. */
    var gateDays = 7

    private fun load(): Campus =
        runCatching { file.takeIf { it.exists() }?.readText()?.let(Vault::decrypt)?.let { CacheJson.decodeFromString(Campus.serializer(), it) } }
            .getOrNull() ?: Campus()

    private fun save(c: Campus) {
        runCatching { file.writeText(Vault.encrypt(CacheJson.encodeToString(Campus.serializer(), c))) }
    }

    fun at(section: Section, c: Campus = _state.value): Long = when (section) {
        Section.People -> c.peopleAt
        Section.Messages -> c.messagesAt
        Section.Spotlight -> c.spotlightAt
        Section.Gate -> c.gateAt
        Section.Money -> c.moneyAt
        Section.Curriculum -> c.curriculumAt
        Section.Grades -> c.gradesAt
        Section.Courses -> c.coursesAt
    }

    /** Fetches [section] unless it's fresh (or [force]). Errors land in [errors], the old data stays. */
    fun refresh(section: Section, force: Boolean = false) {
        if (section in _loading.value) return
        if (!force && section.fresh(at(section))) return
        _loading.update { it + section }
        _errors.update { it - section }
        repo.launch {
            try {
                val now = System.currentTimeMillis()
                val update: (Campus) -> Campus = repo.onVtop { c -> fetch(section, c, now) }
                _state.update(update)
                save(_state.value)
            } catch (e: Exception) {
                _errors.update { it + (section to repo.explain(e)) }
            } finally {
                _loading.update { it - section }
            }
        }
    }

    suspend fun refreshNow(section: Section) {
        val update: (Campus) -> Campus = repo.onVtop { c -> fetch(section, c, System.currentTimeMillis()) }
        _state.update(update)
        save(_state.value)
    }

    private fun fetch(section: Section, c: VtopClient, now: Long): (Campus) -> Campus = when (section) {
        Section.People -> {
            val p = c.proctor()
            val h = runCatching { c.hodDean() }.getOrDefault(emptyList())
            ({ it.copy(proctor = p, hodDean = h, peopleAt = now) })
        }
        Section.Messages -> {
            val cls = c.classMessages()
            val pro = c.proctorMessages()
            ({ it.copy(classMessages = cls, proctorMessages = pro, messagesAt = now) })
        }
        Section.Spotlight -> c.spotlight().let { s -> { it.copy(spotlight = s, spotlightAt = now) } }
        Section.Gate -> {
            val from = LocalDate.now().minusDays(gateDays.toLong())
            val t = c.biometric(from)
            ({ it.copy(gate = t, gateFrom = from.toString(), gateAt = now) })
        }
        Section.Money -> {
            val r = c.receipts()
            val f = runCatching { c.feeLetters() }.getOrDefault(emptyList())
            val l = runCatching { c.libraryDues() }.getOrDefault(emptyList()).map { (k, v) -> LibraryLine(k, v) }
            ({ it.copy(receipts = r, feeLetters = f, library = l, moneyAt = now) })
        }
        Section.Curriculum -> c.curriculum().let { (cats, courses) -> { it.copy(categories = cats, curriculum = courses, curriculumAt = now) } }
        // One semester at a time, on a tap: see [loadSemester].
        Section.Grades -> { it -> it }
        Section.Courses -> c.courseOptions().let { o -> { it.copy(courses = o, coursesAt = now) } }
    }

    /** VTOP's own results page for one semester (two requests), kept for offline. */
    fun loadSemester(semesterId: String, force: Boolean = false) {
        val have = _state.value.semesterGrades[semesterId]
        if (Section.Grades in _loading.value || (have != null && !force)) return
        _loading.update { it + Section.Grades }
        _errors.update { it - Section.Grades }
        repo.launch {
            try {
                val g = repo.onVtop { c -> c.semesterGrades(semesterId) }
                _state.update { it.copy(semesterGrades = it.semesterGrades + (semesterId to g), gradesAt = System.currentTimeMillis()) }
                save(_state.value)
            } catch (e: Exception) {
                _errors.update { it + (Section.Grades to repo.explain(e)) }
            } finally {
                _loading.update { it - Section.Grades }
            }
        }
    }

    /** Debug builds' demo seed only. */
    internal fun showDemo(c: Campus) {
        _state.value = c
    }

    fun wipe() {
        file.delete()
        _state.value = Campus()
    }
}

private fun Repo.launch(block: suspend () -> Unit) = scope.launch { block() }
