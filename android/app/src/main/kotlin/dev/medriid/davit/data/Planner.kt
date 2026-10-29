package dev.medriid.davit.data

import dev.medriid.davit.vtop.GradeHistory

/** VIT's letter grades and their points, best first. */
val GradePoints: List<Pair<String, Int>> = listOf("S" to 10, "A" to 9, "B" to 8, "C" to 7, "D" to 6, "E" to 5, "F" to 0)

private val pointsOf = GradePoints.toMap() + ("N" to 0)

/** Credits and credit-points already on record (letter grades only; P, W and the like don't count). */
fun GradeHistory?.record(): Pair<Double, Double> {
    val graded = this?.entries.orEmpty().filter { it.credits > 0 && it.grade?.uppercase() in pointsOf }
    return graded.sumOf { it.credits } to graded.sumOf { it.credits * pointsOf.getValue(it.grade!!.uppercase()) }
}

/** One exam session's GPA ("Nov-2025"), from the grade history. */
data class SessionGpa(val session: String, val gpa: Double, val credits: Double)

/**
 * GPA per exam session, the closest the grade history comes to a semester: credits ×
 * points over graded credits, as for the CGPA. Sessions with no letter grades are left out.
 */
fun GradeHistory?.sessionGpas(): List<SessionGpa> =
    this?.entries.orEmpty().groupBy { it.examMonth }.mapNotNull { (session, list) ->
        val graded = list.filter { it.credits > 0 && it.grade?.uppercase() in pointsOf }
        val credits = graded.sumOf { it.credits }
        if (credits <= 0 || session.isBlank()) null
        else SessionGpa(session, graded.sumOf { it.credits * pointsOf.getValue(it.grade!!.uppercase()) } / credits, credits)
    }

/** CGPA if this semester's courses ([credits] to grade points) end as planned. Null with no credits at all. */
fun projectCgpa(history: GradeHistory?, planned: List<Pair<Double, Int>>): Double? {
    val (credits, points) = history.record()
    val c = credits + planned.sumOf { it.first }
    if (c <= 0) return null
    return (points + planned.sumOf { it.first * it.second }) / c
}

/**
 * The average grade point this semester's [plannedCredits] need for the CGPA to reach
 * [target]. Can be ≤ 0 (already there whatever happens) or > 10 (out of reach).
 */
fun neededAverage(history: GradeHistory?, plannedCredits: Double, target: Double = 9.0): Double? {
    if (plannedCredits <= 0) return null
    val (credits, points) = history.record()
    return (target * (credits + plannedCredits) - points) / plannedCredits
}
