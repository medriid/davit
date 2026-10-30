package dev.medriid.davit.scan

import kotlin.math.exp
import kotlin.math.pow

/** One try at fitting a scan: the longest side of each page image, and its JPEG quality. */
data class FitStep(val side: Int, val quality: Int)

/**
 * Fitting a scanned PDF under a size limit (VTOP's 4 MB): from sharp to small, but never
 * walking every step. Each try is measured, and the next is the sharpest step that's
 * expected to fit with a little room, so a big scan gets there in two or three tries.
 */
object Fit {
    val ladder = listOf(
        FitStep(2200, 82), FitStep(2000, 78), FitStep(1800, 74), FitStep(1600, 70), FitStep(1400, 64),
        FitStep(1250, 58), FitStep(1100, 52), FitStep(950, 46), FitStep(800, 40), FitStep(680, 36), FitStep(600, 32),
    )

    /** Rough size of [s] against the first step: its area, times how JPEG size falls with quality. Only for choosing. */
    fun weight(s: FitStep): Double = (s.side / 2200.0).pow(2) * exp((s.quality - 82) / 25.0)

    /**
     * After step [at] came out at [size] bytes: the step to try next for [limit], or null
     * when [at] already fits (or nothing smaller is left).
     */
    fun next(at: Int, size: Long, limit: Long): Int? {
        if (size <= limit || at >= ladder.lastIndex) return null
        val perWeight = size / weight(ladder[at])
        val room = limit * 0.95
        return (at + 1..ladder.lastIndex).firstOrNull { perWeight * weight(ladder[it]) <= room } ?: ladder.lastIndex
    }
}
