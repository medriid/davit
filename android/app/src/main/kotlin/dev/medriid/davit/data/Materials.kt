package dev.medriid.davit.data

import android.content.Context
import dev.medriid.davit.search.Fuzzy
import dev.medriid.davit.vtop.CourseMaterials
import dev.medriid.davit.vtop.CourseOption
import dev.medriid.davit.vtop.Material
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One course page as last fetched. */
@Serializable
data class CachedCourse(val course: CourseOption, val page: CourseMaterials, val at: Long)

/** A material with the course it's on, for lists and search. */
data class MaterialRow(val course: CourseOption, val material: Material, val moduleTitle: String, val mine: Boolean) {
    val key: String get() = "mat:" + material.fileId
    val day: LocalDate? get() = runCatching { LocalDate.parse(material.date, DateTimeFormatter.ofPattern("dd-MM-yyyy")) }.getOrNull()
}

/**
 * Course pages: the list of materials per course (cached encrypted, so they show
 * offline), which of them are your own faculty's, downloads, and search across them all.
 */
class MaterialsStore(private val context: Context, private val repo: Repo, val files: FileStore) {
    private val dir = File(context.filesDir, "course-pages").apply { mkdirs() }

    private val _pages = MutableStateFlow(loadAll())
    val pages: StateFlow<Map<String, CachedCourse>> = _pages

    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors

    private fun fileFor(key: String) = File(dir, key.hashCode().toUInt().toString(16) + ".enc")

    private fun loadAll(): Map<String, CachedCourse> =
        dir.listFiles().orEmpty().mapNotNull { f ->
            runCatching { Vault.decrypt(f.readText())?.let { CacheJson.decodeFromString(CachedCourse.serializer(), it) } }.getOrNull()
        }.associateBy { it.course.key }

    /** Fetches [course]'s page unless it's under six hours old (or [force]). */
    fun load(course: CourseOption, force: Boolean = false) {
        val key = course.key
        val have = _pages.value[key]
        if (key in _loading.value) return
        if (!force && have != null && System.currentTimeMillis() - have.at < 6 * 3_600_000L) return
        _loading.update { it + key }
        _errors.update { it - key }
        repo.scope.launch {
            try {
                val page = repo.onVtop { c -> c.courseMaterials(course) }
                val cached = CachedCourse(course, page, System.currentTimeMillis())
                fileFor(key).writeText(Vault.encrypt(CacheJson.encodeToString(CachedCourse.serializer(), cached)))
                _pages.update { it + (key to cached) }
            } catch (e: Exception) {
                _errors.update { it + (key to repo.explain(e)) }
            } finally {
                _loading.update { it - key }
            }
        }
    }

    /** Every course in [courses] not fetched yet (or stale), one after another, for search. */
    fun loadMissing(courses: List<CourseOption>) = courses.forEach { load(it) }

    /**
     * Your faculty for [course], as uppercase names (the timetable's) and employee ids (the
     * faculty cards'). Lab and theory can have different teachers, so the type has to fit.
     */
    fun myFaculty(course: CourseOption, snapshot: Snapshot?): Pair<Set<String>, Set<String>> {
        val tt = snapshot?.timetable.orEmpty().filter { it.courseCode.equals(course.code, ignoreCase = true) }
        val lab = isLabCode(course.type)
        val matched = tt.filter { isLabType(it.courseType) == lab }.ifEmpty { tt }
        val names = matched.map { facultyKey(it.faculty) }.filter { it.isNotEmpty() }.toSet()
        val ids = snapshot?.faculty.orEmpty().filter { facultyKey(it.name) in names }.map { it.empId }.toSet()
        return names to ids
    }

    /** Rows for one course, your faculty's first, then newest first. */
    fun rows(cached: CachedCourse, snapshot: Snapshot?): List<MaterialRow> {
        val (names, ids) = myFaculty(cached.course, snapshot)
        return cached.page.materials.map { m ->
            val mine = (m.uploaderId.isNotEmpty() && m.uploaderId in ids) || facultyKey(m.uploader) in names
            MaterialRow(cached.course, m, cached.page.modules[m.module].orEmpty(), mine)
        }.sortedWith(compareByDescending<MaterialRow> { it.mine }.thenByDescending { it.day })
    }

    fun allRows(snapshot: Snapshot?): List<MaterialRow> = _pages.value.values.flatMap { rows(it, snapshot) }

    /** A search index over [rows]: title over module over faculty over course. Build it off the main thread. */
    fun index(rows: List<MaterialRow>): Fuzzy.Index<MaterialRow> = Fuzzy.Index(rows, SearchFields) { r ->
        listOf(
            r.material.title,
            listOf("Module ${r.material.module}", r.moduleTitle).filter { it.isNotBlank() && it != "Module " }.joinToString(" "),
            r.material.uploader,
            "${r.course.code} ${r.course.name} ${r.material.kind}",
        )
    }

    // ---- recent searches ---------------------------------------------------------------

    private val prefs = context.getSharedPreferences("davit", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(
        prefs.getString(K_RECENT, null)?.let(Vault::decrypt)
            ?.let { runCatching { CacheJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty(),
    )
    val recent: StateFlow<List<String>> = _recent

    fun remember(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        _recent.update { (listOf(q) + it.filterNot { r -> r.equals(q, ignoreCase = true) }).take(8) }
        prefs.edit().putString(K_RECENT, Vault.encrypt(CacheJson.encodeToString(ListSerializer(String.serializer()), _recent.value))).apply()
    }

    fun forgetRecent() {
        _recent.value = emptyList()
        prefs.edit().remove(K_RECENT).apply()
    }

    /** Debug builds' demo seed only. */
    internal fun showDemo(pages: List<CachedCourse>) {
        _pages.value = pages.associateBy { it.course.key }
    }

    fun wipe() {
        dir.deleteRecursively()
        dir.mkdirs()
        _pages.value = emptyMap()
        forgetRecent()
    }

    companion object {
        private const val K_RECENT = "recentSearches"
        const val TITLE = "title"
        const val MODULE = "module"
        const val FACULTY = "faculty"
        const val COURSE = "course"
        val SearchFields = listOf(Fuzzy.Field(TITLE, 3f), Fuzzy.Field(MODULE, 2f), Fuzzy.Field(FACULTY, 1.5f), Fuzzy.Field(COURSE, 1.5f))
    }
}
