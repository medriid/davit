package dev.medriid.davit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** The printed sheet by date, and what the bell sets from it. */
class MessTest {
    private val mess = Messes.all.first()
    private fun menu(day: Int) = mess.on(LocalDate.of(2026, 11, day)) as MessDay.Menu

    @Test
    fun aDateFindsItsRow() {
        assertEquals("Mix Veg Paratha", menu(1).meals[Meal.Breakfast]?.first())
        assertEquals(menu(1), menu(15))
        assertEquals(menu(1), menu(29))
        assertEquals("Pani Puri (5 Nos)", menu(22).meals[Meal.Snacks]?.single())
        assertEquals("White Channa Chat", menu(23).meals[Meal.Snacks]?.single())
    }

    @Test
    fun theThirtiethIsLikeTheSecond() {
        assertEquals("Poori", menu(30).meals[Meal.Breakfast]?.first())
        assertEquals(menu(2), menu(30))
    }

    @Test
    fun aRowOnlyCoversOneWeekday() {
        for (row in mess.sheets.single().rows) {
            val weekdays = row.dates.map { LocalDate.of(2026, 11, it).dayOfWeek }.toSet()
            assertEquals(row.dates.toString(), 1, weekdays.size)
        }
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 11, 1).dayOfWeek)
    }

    @Test
    fun aGapDayIsNotOnTheSheet() {
        val november = YearMonth.of(2026, 11)
        (10..14).plus(24..28).forEach { assertEquals(MessDay.NotOnSheet(november), mess.on(LocalDate.of(2026, 11, it))) }
    }

    @Test
    fun anotherMonthHasNoSheet() {
        assertEquals(MessDay.NoSheet, mess.on(LocalDate.of(2026, 12, 1)))
        assertEquals(MessDay.NoSheet, mess.on(LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun theDayReads() {
        assertEquals("Sun, 1st Nov", messDayLabel(LocalDate.of(2026, 11, 1)))
        assertEquals("Mon, 2nd Nov", messDayLabel(LocalDate.of(2026, 11, 2)))
        assertEquals("Tue, 3rd Nov", messDayLabel(LocalDate.of(2026, 11, 3)))
        assertEquals("Wed, 11th Nov", messDayLabel(LocalDate.of(2026, 11, 11)))
        assertEquals("Thu, 12th Nov", messDayLabel(LocalDate.of(2026, 11, 12)))
        assertEquals("Sat, 21st Nov", messDayLabel(LocalDate.of(2026, 11, 21)))
        assertEquals("Mon, 30th Nov", messDayLabel(LocalDate.of(2026, 11, 30)))
    }

    @Test
    fun noMealsPickedNoReminders() {
        assertTrue(mealReminders(AlertPrefs(), LocalDateTime.of(2026, 11, 1, 6, 0)).isEmpty())
    }

    @Test
    fun remindersAreEarlyByWhatYouPicked() {
        val prefs = AlertPrefs(meals = setOf("Lunch"), beforeMeal = 30)
        val first = mealReminders(prefs, LocalDateTime.of(2026, 11, 1, 6, 0)).first()
        assertEquals(LocalDateTime.of(2026, 11, 1, 12, 0), first.at)
        assertEquals("Lunch at 12:30 PM", first.alert.title)
        assertTrue(first.alert.text.startsWith("Phulka, Dhal"))
        assertEquals(AlertChannel.Meals, first.alert.channel)
    }

    @Test
    fun aMealWithNoKnownTimeNeverReminds() {
        assertFalse(Meal.Dinner in mess.times)
        assertTrue(mealReminders(AlertPrefs(meals = setOf("Dinner")), LocalDateTime.of(2026, 11, 1, 6, 0)).isEmpty())
    }

    @Test
    fun aGapIsSkippedToTheNextMeal() {
        val prefs = AlertPrefs(meals = setOf("Breakfast"), beforeMeal = 0)
        assertEquals(LocalDateTime.of(2026, 11, 15, 7, 30), mealReminders(prefs, LocalDateTime.of(2026, 11, 9, 20, 0)).first().at)
    }

    @Test
    fun aMealAlreadyPastIsSkipped() {
        val prefs = AlertPrefs(meals = setOf("Lunch"), beforeMeal = 0)
        assertEquals(LocalDateTime.of(2026, 11, 2, 12, 30), mealReminders(prefs, LocalDateTime.of(2026, 11, 1, 12, 30)).first().at)
    }

    @Test
    fun mealChoicesSurviveSaving() {
        val prefs = AlertPrefs(meals = setOf("Lunch", "Snacks"), beforeMeal = 30, mess = "mens-special")
        assertEquals(prefs, AlertPrefs.fromJson(prefs.toJson()))
    }
}
