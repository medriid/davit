package dev.medriid.davit.data

import dev.medriid.davit.vtop.Circular
import dev.medriid.davit.vtop.FacultyProject
import dev.medriid.davit.vtop.VtopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WatchAlertsTest {
    private val today = LocalDate.of(2026, 11, 2)
    private fun circ(id: String) = Circular(id, "Title $id", "2026-11-02", "Office", "General")
    private fun proj(id: String, area: String) = FacultyProject(id, "Project $id", area, "2026-11-02", "Teacher", "SCOPE")
    private fun win(open: Boolean) = VtopWindow("mooc", "MOOC registration", open, "")

    @Test
    fun aNewCircularAlertsOnce() {
        val a = watchAlerts(HomeFeeds(circulars = listOf(circ("1"))), HomeFeeds(circulars = listOf(circ("1"), circ("2"))), today, AlertPrefs())
        assertEquals("circ:2", a.single().key)
    }

    @Test
    fun theFirstReadingIsQuiet() {
        assertTrue(watchAlerts(HomeFeeds(), HomeFeeds(circulars = listOf(circ("1"), circ("2"))), today, AlertPrefs()).isEmpty())
    }

    @Test
    fun manyCircularsBecomeOneAlert() {
        val many = (2..6).map { circ("$it") }
        val a = watchAlerts(HomeFeeds(circulars = listOf(circ("1"))), HomeFeeds(circulars = many), today, AlertPrefs())
        assertEquals("5 new circulars", a.single().title)
    }

    @Test
    fun projectsOnlyInFollowedAreas() {
        val old = HomeFeeds(projects = listOf(proj("1", "Mathematics")))
        val new = HomeFeeds(projects = old.projects + proj("2", "Mathematics") + proj("3", "Computer Science"))
        assertTrue(watchAlerts(old, new, today, AlertPrefs()).isEmpty())
        assertEquals("proj:3", watchAlerts(old, new, today, AlertPrefs(projectAreas = setOf("Computer Science"))).single().key)
    }

    @Test
    fun aWindowAlertsWhenItOpensNotWhileOpenOrOnFirstSight() {
        assertEquals("win:mooc|2026-11-02", watchAlerts(HomeFeeds(windows = listOf(win(false))), HomeFeeds(windows = listOf(win(true))), today, AlertPrefs()).single().key)
        assertTrue(watchAlerts(HomeFeeds(windows = listOf(win(true))), HomeFeeds(windows = listOf(win(true))), today, AlertPrefs()).isEmpty())
        assertTrue(watchAlerts(HomeFeeds(), HomeFeeds(windows = listOf(win(true))), today, AlertPrefs()).isEmpty())
    }

    @Test
    fun aFailedOrEmptyReadingKeepsTheLastList() {
        val f = HomeFeeds(circulars = listOf(circ("1")), watchAt = 5)
        val failed = f.withWatched(null, emptyList(), listOf(win(true)), 9)
        assertEquals(1, failed.circulars.size)
        assertEquals(5L, failed.watchAt)
        assertEquals(1, f.withWatched(emptyList(), emptyList(), emptyList(), 9).circulars.size)
    }

    @Test
    fun followedAreasSurviveSaving() {
        assertEquals(setOf("Mathematics"), AlertPrefs.fromJson(AlertPrefs(projectAreas = setOf("Mathematics")).toJson()).projectAreas)
    }
}
