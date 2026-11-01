package dev.medriid.davit.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FacultyReply(val text: String, val date: String)

data class FacultyReview(
    val rating: Int = 0,
    val text: String = "",
    val course: String = "",
    val courseName: String = "",
    val date: String = "",
    /** "site" for written on No8AM, "reddit" or "compiled" for gathered from elsewhere. */
    val source: String = "site",
    val paraphrased: Boolean = false,
    /** Grade to the mark it took, "s" to "f", where the reviewer gave them. */
    val cutoffs: Map<String, String> = emptyMap(),
    val classStrength: String? = null,
    val classMean: String? = null,
    val classSd: String? = null,
    val replies: List<FacultyReply> = emptyList(),
)

data class ReviewedFaculty(
    val id: String,
    val empId: String = "",
    val name: String = "",
    val department: String = "",
    val designation: String = "",
    val campus: String = "",
    val rating: Double = 0.0,
    val reviewCount: Int = 0,
    val reviews: List<FacultyReview> = emptyList(),
)

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else optString(key)
private inline fun <T> JSONArray?.items(read: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else List(length()) { read(getJSONObject(it)) }

private fun readReview(o: JSONObject) = FacultyReview(
    rating = o.optInt("rating"),
    text = o.text("text"),
    course = o.text("course"),
    courseName = o.text("courseName"),
    date = o.text("date"),
    source = o.text("source").ifEmpty { "site" },
    paraphrased = o.optBoolean("paraphrased"),
    cutoffs = o.optJSONObject("cutoffs")?.let { c -> c.keys().asSequence().associateWith { c.text(it) } }.orEmpty(),
    classStrength = o.textOrNull("classStrength"),
    classMean = o.textOrNull("classMean"),
    classSd = o.textOrNull("classSd"),
    replies = o.optJSONArray("replies").items { FacultyReply(it.text("text"), it.text("date")) },
)

/** Names as VTOP and No8AM each write them can order initials differently, so the parts are sorted. */
internal fun facultyMatchKey(name: String): String = facultyKey(name).split(' ').sorted().joinToString(" ")

/** No8AM's snapshot, with the ways the app looks a teacher up. */
class FacultyBook(val faculty: List<ReviewedFaculty>, val snapshotAt: String = "") {
    private val byId = faculty.associateBy { it.id }
    private val byEmpId = faculty.filter { it.empId.isNotBlank() }.associateBy { it.empId.trim().uppercase() }

    // VTOP here is Vellore's. A name two Vellore teachers share finds neither: no rating beats the wrong teacher's.
    private val byName = faculty.filter { it.campus == "vellore" }.groupBy { facultyMatchKey(it.name) }
        .filterValues { it.size == 1 }.mapValues { it.value.single() }

    // Built here, off the main thread, so typing in the search stays quick.
    private val haystacks = faculty.associate { f ->
        f.id to (listOf(f.name, f.department) + f.reviews.flatMap { listOf(it.course, it.courseName) }).joinToString(" ").lowercase()
    }

    fun byId(id: String): ReviewedFaculty? = byId[id]

    /** A VTOP teacher: by employee id if No8AM has it, else by a name only one teacher has. */
    fun find(empId: String?, name: String): ReviewedFaculty? =
        empId?.trim()?.uppercase()?.let { byEmpId[it] } ?: byName[facultyMatchKey(name)]

    /** Most reviewed first. With no [query], only teachers who have reviews. */
    fun search(query: String, campus: String): List<ReviewedFaculty> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        return faculty.asSequence()
            .filter { it.campus == campus }
            .filter { f -> if (words.isEmpty()) f.reviewCount > 0 else haystacks[f.id].orEmpty().let { h -> words.all { it in h } } }
            .sortedWith(compareByDescending<ReviewedFaculty> { it.reviewCount }.thenBy { it.name })
            .toList()
    }

    companion object {
        fun parse(json: String): FacultyBook {
            val root = JSONObject(json)
            val faculty = root.getJSONArray("faculty").items { o ->
                ReviewedFaculty(
                    id = o.getString("id"),
                    empId = o.text("empId"),
                    name = o.text("name"),
                    department = o.text("department"),
                    designation = o.text("designation"),
                    campus = o.text("campus"),
                    rating = o.optDouble("rating", 0.0),
                    reviewCount = o.optInt("reviewCount"),
                    reviews = o.optJSONArray("reviews").items(::readReview),
                )
            }
            return FacultyBook(faculty, root.text("snapshotAt"))
        }
    }
}

/** The snapshot ships in the app, so reviews read with no connection. Read once, on first use. */
class FacultyReviewStore(private val context: Context) {
    private val _book = MutableStateFlow<FacultyBook?>(null)
    val book: StateFlow<FacultyBook?> = _book
    private val lock = Mutex()

    suspend fun load() = lock.withLock {
        if (_book.value != null) return@withLock
        _book.value = withContext(Dispatchers.IO) {
            FacultyBook.parse(context.assets.open("faculty-reviews/reviews.json").bufferedReader().use { it.readText() })
        }
    }
}
