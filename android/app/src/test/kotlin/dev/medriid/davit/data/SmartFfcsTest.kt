package dev.medriid.davit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFCS smart mode against the bundled slot table. A1 is Monday 8:00 and Wednesday 9:00, A2
 * Monday 14:00 and Wednesday 15:00, D1 Monday 10:00 and Thursday 8:00, D2 Monday 16:00 and
 * Thursday 14:00, L1+L2 Monday 8:00–9:40, L31+L32 Monday 14:00–15:40.
 */
class SmartFfcsTest {
    private fun pick(code: String, slot: String, faculty: String, type: String = "ETH") = PlanPick(code, code, slot, "SJT101", faculty, type)

    private fun found(r: SmartResult) = (r as SmartResult.Found).timetables

    @Test
    fun itAvoidsClashes() {
        // Theory in A1 or A2; the lab only in L1+L2, which clashes with A1 on Monday at 8:00.
        val options = mapOf(
            "X" to listOf(pick("X", "A1", "ASHA"), pick("X", "A2", "ASHA")),
            "Y" to listOf(pick("Y", "L1+L2", "RAMAN", "LO")),
        )
        val best = found(findTimetables(listOf(SmartSubject("X", "X"), SmartSubject("Y", "Y")), options, SmartRules())).first()
        assertEquals("A2", best.picks.first { it.code == "X" }.slot)
    }

    @Test
    fun noEightAmStartsMeansTheLaterSlot() {
        val options = mapOf("X" to listOf(pick("X", "D1", "ASHA"), pick("X", "D2", "ASHA")))
        val list = found(findTimetables(listOf(SmartSubject("X", "X")), options, SmartRules(eightAmDays = 0)))
        assertEquals(listOf("D2"), list.map { it.picks.single().slot })
    }

    @Test
    fun aStartTimeRuleDropsEarlySlots() {
        val options = mapOf("X" to listOf(pick("X", "A1", "ASHA"), pick("X", "A2", "ASHA")))
        val list = found(findTimetables(listOf(SmartSubject("X", "X")), options, SmartRules(earliest = 9 * 60)))
        assertEquals(listOf("A2"), list.map { it.picks.single().slot })
    }

    @Test
    fun lunchIsShortOnlyWhenTheMorningRunsUpToItAndTheAfternoonStartsAtTwo() {
        assertTrue(shortLunch(listOf(11 * 60 + 40 to 13 * 60 + 20, 14 * 60 to 15 * 60 + 40)))
        assertTrue(shortLunch(listOf(12 * 60 to 12 * 60 + 50, 14 * 60 to 14 * 60 + 50)))
        assertFalse(shortLunch(listOf(11 * 60 + 40 to 13 * 60 + 20, 15 * 60 to 15 * 60 + 50)))
        assertFalse(shortLunch(listOf(10 * 60 to 10 * 60 + 50, 14 * 60 to 14 * 60 + 50)))
        assertFalse(shortLunch(listOf(14 * 60 to 14 * 60 + 50)))
    }

    @Test
    fun theoryAndLabGoToTheSameFacultyWhereTheyCan() {
        val options = mapOf(
            "X" to listOf(pick("X", "D1", "ASHA"), pick("X", "D1", "MEERA"), pick("X", "L31+L32", "MEERA", "ELA")),
        )
        val best = found(findTimetables(listOf(SmartSubject("X", "X")), options, SmartRules())).first()
        assertEquals(setOf("MEERA"), best.picks.map { it.faculty }.toSet())
        assertEquals(0, best.splitFaculty)
    }

    @Test
    fun onlyTheFacultyYouPick() {
        val options = mapOf("X" to listOf(pick("X", "A1", "ASHA"), pick("X", "A2", "RAMAN")))
        val list = found(findTimetables(listOf(SmartSubject("X", "X", setOf("Raman"))), options, SmartRules()))
        assertEquals(listOf("RAMAN"), list.map { it.picks.single().faculty })
    }

    @Test
    fun whenNothingFitsItSaysWhichRuleToLoosen() {
        // Only 8:00 slots, and no 8:00 starts allowed.
        val options = mapOf("X" to listOf(pick("X", "A1", "ASHA"), pick("X", "D1", "ASHA")))
        val r = findTimetables(listOf(SmartSubject("X", "X")), options, SmartRules(eightAmDays = 0))
        assertEquals(listOf("8:00 starts"), (r as SmartResult.None).relax)
    }

    @Test
    fun aRealSizedSemesterIsSearchedQuickly() {
        // Four embedded courses, each with 4 theory slots and every one of the 30 lab pairs: ~450 million mixes.
        val theory = listOf("A1+TA1", "B1+TB1", "C1+TC1", "D1+TD1", "A2+TA2", "B2+TB2", "C2+TC2", "D2+TD2")
        val labs = (1..59 step 2).map { "L$it+L${it + 1}" }
        val codes = listOf("P", "Q", "R", "S")
        val options = codes.mapIndexed { n, code ->
            code to (theory.drop(n).take(4).map { pick(code, it, "F$n") } + labs.map { pick(code, it, "F$n", "ELA") })
        }.toMap()
        val start = System.currentTimeMillis()
        val r = findTimetables(codes.map { SmartSubject(it, it) }, options, SmartRules(eightAmDays = 1, shortLunchDays = 1))
        val took = System.currentTimeMillis() - start
        val list = found(r)
        assertEquals(10, list.size)
        assertTrue("took $took ms", took < 15_000)
        list.forEach { t ->
            assertTrue(t.eightAmDays <= 1 && t.shortLunchDays <= 1)
            assertEquals(emptyList<Clash>(), t.picks.clashes())
        }
    }

    @Test
    fun theSetupSurvivesSaving() {
        val s = SmartSetup(listOf(SmartSubject("X", "Data Structures", setOf("ASHA"))), SmartRules(eightAmDays = 1, earliest = 9 * 60, latest = 17 * 60 + 30, shortLunchDays = 2, daysOff = false))
        assertEquals(s, smartFromJson(smartToJson(s)))
    }
}
