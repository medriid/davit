package dev.medriid.davit.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Meal(val title: String) {
    Breakfast("Breakfast"),
    Lunch("Lunch"),
    Snacks("Snacks"),
    Dinner("Dinner"),
}

data class MealTime(val start: LocalTime, val end: LocalTime)

/** One row of a mess's printed sheet: the days of the month it covers and what each meal is. */
data class MessRow(val dates: List<Int>, val meals: Map<Meal, List<String>>)

/** One month's printed sheet. A day without a row isn't on the sheet: nothing is filled in for it. */
data class MessSheet(val month: YearMonth, val rows: List<MessRow>)

data class Mess(
    val id: String,
    val name: String,
    val hostel: String,
    /** Only what's known; a meal missing here shows no time and can't remind you. */
    val times: Map<Meal, MealTime>,
    /** Where the times came from, when they aren't this mess's own. */
    val timesNote: String?,
    val sheets: List<MessSheet>,
)

/** What a mess serves on one date, as far as its sheets say. */
sealed interface MessDay {
    data class Menu(val meals: Map<Meal, List<String>>) : MessDay

    /** There's a sheet for the month, but it has no row for this date. */
    data class NotOnSheet(val month: YearMonth) : MessDay

    /** No sheet for the month at all. */
    data object NoSheet : MessDay
}

fun Mess.on(date: LocalDate): MessDay {
    val sheet = sheets.firstOrNull { it.month == YearMonth.from(date) } ?: return MessDay.NoSheet
    val row = sheet.rows.firstOrNull { date.dayOfMonth in it.dates } ?: return MessDay.NotOnSheet(sheet.month)
    return MessDay.Menu(row.meals)
}

private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** "Sun, 1st Nov". */
fun messDayLabel(date: LocalDate): String {
    val n = date.dayOfMonth
    val suffix = when {
        n in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    val day = date.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))
    val month = date.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))
    return "$day, $n$suffix $month"
}

fun MealTime.startLabel(): String = start.format(clock)

/** "7:30 AM to 9:30 AM". */
fun MealTime.label(): String = "${start.format(clock)} to ${end.format(clock)}"

data class MealReminder(val at: LocalDateTime, val alert: Alert)

/**
 * The next reminders for the meals you picked, soonest first. A month ahead, so a stretch
 * the sheet doesn't cover still leaves the first meal after it set. Nothing for a day
 * without a menu or a meal without a known time.
 */
fun mealReminders(prefs: AlertPrefs, now: LocalDateTime): List<MealReminder> {
    if (prefs.meals.isEmpty()) return emptyList()
    val mess = Messes.of(prefs.mess)
    return (0L..31L).flatMap { offset ->
        val date = now.toLocalDate().plusDays(offset)
        val menu = mess.on(date) as? MessDay.Menu ?: return@flatMap emptyList()
        Meal.entries.filter { it.name in prefs.meals }.mapNotNull { meal ->
            val time = mess.times[meal] ?: return@mapNotNull null
            val at = date.atTime(time.start).minusMinutes(prefs.beforeMeal.toLong())
            if (!at.isAfter(now)) return@mapNotNull null
            MealReminder(
                at,
                Alert(
                    AlertChannel.Meals,
                    "meal:${mess.id}|$date|${meal.name}",
                    "${meal.title} at ${time.startLabel()}",
                    menu.meals[meal].orEmpty().joinToString(", "),
                ),
            )
        }
    }.sortedBy { it.at }
}

private fun row(vararg dates: Int, breakfast: String, lunch: String, snacks: String, dinner: String) = MessRow(
    dates.toList(),
    mapOf(
        Meal.Breakfast to breakfast.split(", "),
        Meal.Lunch to lunch.split(", "),
        Meal.Snacks to snacks.split(", "),
        Meal.Dinner to dinner.split(", "),
    ),
)

/** The men's hostel Special Mess sheet for November 2026. It prints no rows for the 10th to 14th or the 24th to 28th. */
private val specialNovember2026 = MessSheet(
    YearMonth.of(2026, 11),
    listOf(
        row(
            1, 15, 29,
            breakfast = "Mix Veg Paratha, Curd, Pickle, Kichidi, Sambar, Chutney, Chocos, Vegetable Salad, Egg bhurji, Water Melon Juice",
            lunch = "Phulka, Dhal, White Rice, Rasam, Appalam, Onion Raitha, Oil Brinjal, Soya Biriyani, Paneer 65, Chicken Biriyani, Ice Cream",
            snacks = "Veg Puff",
            dinner = "Chapathi, Dhal Punjabi, White Rice, Sambar, Rasam, Loose Curd, Peas Pulao, Mixed Veg gravy, Veg Clear Soup, Papaya",
        ),
        row(
            2, 16, 30,
            breakfast = "Poori, Potato Masala, Semiya Kichidi, Chutney, Sundal, Corn Flakes, French Toast, Pineapple Juice",
            lunch = "Pulka, Toor Dhal, Butter Chicken Masala, Poriyal, Paneer Butter Masala, Butter milk, White Rice, Sambar, Rasam, wheel chips, Mothichoor Laddu",
            snacks = "Onion Pakoda",
            dinner = "Roti, Dhal, Poriyal, Onion Uthappam, White Rice, Sambar, Rasam, Tomato Chutney, Jeera Rice, Veg Noodle Soup, Seasonal Fruit",
        ),
        row(
            3, 17,
            breakfast = "Kichidi, Podi Idly, Sambar, Chutney, Masala Omelette, Dates Milkshake, Sauthe Black Chenna Sundal, Chocos",
            lunch = "Pulka, Palak green gram dal, Bisibela Bath, Curd, Potato Chips, White Rice, Rasam, Veg Kolhapuri, Appalam, Badhusa, Poriyal",
            snacks = "Mysore Bonda, Chutney",
            dinner = "Chapathi, Dhal, Veg & Egg Fried Rice, White Rice, Sambar, Rasam, Butter Milk, Baby Corn Manchurian, Poriyal, Hot and Sour Soup, Water Melon",
        ),
        row(
            4, 18,
            breakfast = "Pav Bhaji, Pongal, Sambar, Chutney, Vada, Veg Salad, Corn Flakes, Boiled Egg, Grape Juice",
            lunch = "Phulka, Chicken Chettinadu, Palak Paneer, Dhal, White Rice, Radish Sambar, Rasam, Curd, Poriyal, Rice Papad, Milk Peda",
            snacks = "Spring roll with sauce",
            dinner = "Phulka, Dhal, Aloo capsicum masala, Veg Pulav, White Rice, Sambar, Rasam, Loose curd, Knol Khol Poriyal, Mix Veg Soup, Banana",
        ),
        row(
            5, 19,
            breakfast = "Set Dosa, Vada Curry, Groundnut Chutney, Poha Namkeen, Chocos, Sprouted Moong Dhal, Scrambled Egg, Water Melon Juice",
            lunch = "Chapathi, Dhal Makhani, Malabar Fish Curry, Nargesi Kofta Curry, White Rice, Kara Kulambu, Rasam, Poriyal, Curd, Fryums, Rasamalai",
            snacks = "Sweet corn chat",
            dinner = "Poori, Chenna Masala, Vangi Bath, White Rice, Sambar, Rasam, Loose Curd, Poriyal, Cream of Broccoli Soup, Seasonal Fruit",
        ),
        row(
            6, 20,
            breakfast = "Mixed Veg Paratha, Curd, Rava kitchadi, Chutney, Fresh Juice, Peanut Sundal, Podimas, Corn Flakes",
            lunch = "Phulka, Rajma Dhal, Tandoori Chicken, Poriyal, Paneer Amritsari, White Rice, Sambar, Rasam, Curd, Mix Veg Gravy, Rice Papad, Bread halwa",
            snacks = "Donut",
            dinner = "Phulka, Dhal, Rava Idly, Gosthu, White rice, Sambar, Rasam, Dum Aloo, Butter Milk, Palak Soup, Banana",
        ),
        row(
            7, 21,
            breakfast = "Podi Dosa, Sambar, Chutney, Boiled Egg Masala, Sundal, Fresh Juice, Chocos",
            lunch = "Chapathi, Masoor Dhal, White Rice, Green Peas Masala, Sambar, Poriyal, Rasam, Dahi Vada, Appalam, Dry Jamun",
            snacks = "French Fries",
            dinner = "Phulka, Dhal, Aloo Capsicum Peas Masala, White Rice, Sambar, Rasam, Loose Curd, Dhal Fry, Mint Pulav, Veg Manchow Soup, Egg Fried Rice, Papaya",
        ),
        row(
            8, 22,
            breakfast = "Methi Poori, Chickpea Masala, Boiled Egg, Corn Flakes, Green Gram Sprouts, Fresh Juice",
            lunch = "Phulka, Dhal Fry, White Rice, Brinjal Gravy, Rasam, Fryums, Onion Raitha, Chicken Dum Biriyani, Vegetable Dum Biriyani, Paneer Tikka, Ice Cream",
            snacks = "Pani Puri (5 Nos)",
            dinner = "Phulka, Dhal, Veg Jalfrezi, White Rice, Rasam, Loose Curd, Kashmiri Pulav, Peerkangai Poriyal, Carrot Ginger Soup, Water Melon",
        ),
        row(
            9, 23,
            breakfast = "Aloo Paratha, Curd, Vermicelli upma, Coconut Chutney, Chocos, Vegetable Salad, Boiled Egg Masala, Musk Melon Juice",
            lunch = "Phulka, Urad Dhal, White Rice, Curd, Poriyal, Sambar, Rasam, Kadai Paneer, Makkan Peda, Kadai Chicken Masala, Rice Papad",
            snacks = "White Channa Chat",
            dinner = "Phulka, Dhal Rajma, Podi Dosa, Curd, Coriander Chutney, White Rice, Sambar, Rasam, Butter Milk, Sweet Corn Soup, Poriyal, Banana",
        ),
    ),
)

/** Every mess daVIT has a sheet for. A new mess or month is one more entry here. */
object Messes {
    val all = listOf(
        Mess(
            id = "mens-special",
            name = "Special Mess",
            hostel = "Men's hostel",
            times = mapOf(
                Meal.Breakfast to MealTime(LocalTime.of(7, 30), LocalTime.of(9, 30)),
                Meal.Lunch to MealTime(LocalTime.of(12, 30), LocalTime.of(14, 30)),
                Meal.Snacks to MealTime(LocalTime.of(16, 30), LocalTime.of(18, 15)),
            ),
            timesNote = "Times are the Non-Veg Mess's and may differ here. Dinner's isn't known yet.",
            sheets = listOf(specialNovember2026),
        ),
    )

    fun of(id: String): Mess = all.firstOrNull { it.id == id } ?: all.first()
}
