package dev.medriid.davit.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    private data class Doc(val title: String, val module: String, val faculty: String, val course: String)

    private val docs = listOf(
        Doc("Experiment 9 - Python loops", "Module 3", "ASHA RAMAN", "BXYZ102 Programming Basics"),
        Doc("Module 2 Battery chemistry", "Module 2", "VIKRAM SETH", "BXYZ101 Applied Science"),
        Doc("Thermodynamics notes part 1", "Module 1", "ASHA RAMAN", "BXYZ101 Applied Science"),
        Doc("Data Structures and Algorithms intro", "Module 1", "MEERA NAIR", "BXYZ201 Data Structures"),
        Doc("Experiment 4 - Titration", "Module 4", "VIKRAM SETH", "BXYZ101 Applied Science"),
    )
    private val fields = listOf(Fuzzy.Field("title", 3f), Fuzzy.Field("module", 2f), Fuzzy.Field("faculty", 1.5f), Fuzzy.Field("course", 1.5f))
    private val index = Fuzzy.Index(docs, fields) { listOf(it.title, it.module, it.faculty, it.course) }

    private fun titles(q: String) = index.search(q).map { it.item.title }

    @Test
    fun typosStillFind() {
        assertEquals("Thermodynamics notes part 1", titles("thermodynamcs").first())
        assertEquals("Module 2 Battery chemistry", titles("batery").first())
        assertTrue("Experiment 9 - Python loops" in titles("experment"))
    }

    @Test
    fun everyWordHasToLand() {
        assertEquals(listOf("Experiment 9 - Python loops"), titles("experiment 9"))
        assertEquals(listOf("Experiment 4 - Titration"), titles("exp 4"))
        assertTrue(titles("experiment zebra").isEmpty())
    }

    @Test
    fun numbersMustMatchExactly() {
        assertEquals(listOf("Experiment 4 - Titration"), titles("module 4"))
    }

    @Test
    fun facultyCourseAndInitials() {
        assertEquals(setOf("Experiment 9 - Python loops", "Thermodynamics notes part 1"), titles("asha").toSet())
        assertEquals("Data Structures and Algorithms intro", titles("dsa").first())
        assertTrue(titles("bxyz101").size == 3)
    }

    @Test
    fun rangesPointAtTheMatch() {
        val hit = index.search("therm").first()
        assertEquals(listOf(0 until 5), hit.ranges["title"])
        val typo = index.search("chemistyr").first()
        assertEquals("Module 2 Battery chemistry", typo.item.title)
        assertEquals(listOf(17 until 26), typo.ranges["title"])
    }

    @Test
    fun filterRunsFirstAndBlankListsAll() {
        assertEquals(5, index.search("").size)
        assertEquals(listOf("Thermodynamics notes part 1"), index.search("notes") { it.faculty == "ASHA RAMAN" }.map { it.item.title })
        assertTrue(index.search("notes") { it.faculty == "MEERA NAIR" }.isEmpty())
    }

    @Test
    fun distanceCountsASwapOnce() {
        assertEquals(1, Fuzzy.distance("chemsitry", "chemistry", 2))
        assertEquals(3, Fuzzy.distance("abc", "xyz", 2))
        assertEquals(0f, Fuzzy.wordScore("mod", "zzz"))
    }
}
