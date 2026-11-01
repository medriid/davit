package dev.medriid.davit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Finding a VTOP teacher in No8AM's snapshot: a wrong match would show someone else's reviews. */
class FacultyReviewsTest {
    private fun teacher(id: String, emp: String, name: String, campus: String = "vellore", reviews: Int = 0) =
        ReviewedFaculty(id, emp, name, "SCOPE", campus = campus, reviewCount = reviews, reviews = List(reviews) { FacultyReview(rating = 4, course = "BCSE101L", courseName = "Python") })

    private val book = FacultyBook(
        listOf(
            teacher("1", "V1001", "Dr. Asha Raman", reviews = 2),
            teacher("2", "V1002", "Dr. Ravi S"),
            teacher("3", "V1003", "Mr. Ravi S"),
            teacher("4", "CHN100", "Dr. Meena K", campus = "chennai", reviews = 1),
        ),
    )

    @Test fun `employee id finds a teacher on either campus`() {
        assertEquals("4", book.find("chn100", "someone else")?.id)
    }

    @Test fun `name finds a teacher when the id does not and the order differs`() {
        assertEquals("1", book.find("50123", "RAMAN ASHA")?.id)
    }

    @Test fun `a name two teachers share finds neither`() {
        assertNull(book.find(null, "Ravi S"))
    }

    @Test fun `a name is only matched in Vellore`() {
        assertNull(book.find(null, "Meena K"))
    }

    @Test fun `blank search lists only reviewed teachers on the campus`() {
        assertEquals(listOf("1"), book.search("", "vellore").map { it.id })
    }

    @Test fun `search reads names and course codes`() {
        assertEquals(listOf("2", "3"), book.search("ravi", "vellore").map { it.id })
        assertEquals(listOf("1"), book.search("bcse101", "vellore").map { it.id })
    }

    @Test fun `the bundled file parses in full with no personal fields`() {
        val text = File("src/main/assets/faculty-reviews/reviews.json").readText()
        val all = FacultyBook.parse(text).faculty
        assertEquals(3116, all.size)
        assertEquals(4767, all.sumOf { it.reviews.size })
        all.forEach { assertEquals(it.reviewCount, it.reviews.size) }
        listOf("\"email\"", "\"username\"", "\"user_id\"", "\"avatar_seed\"").forEach { assertFalse(it, it in text) }
    }
}
