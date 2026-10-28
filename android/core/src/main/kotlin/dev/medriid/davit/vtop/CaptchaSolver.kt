package dev.medriid.davit.vtop

import java.io.DataInputStream

/** Decoded image as packed ARGB ints, row-major. */
class Pixels(val width: Int, val height: Int, val argb: IntArray)

/** Platform image decoding: BitmapFactory on Android, ImageIO in tests. */
fun interface ImageDecoder {
    fun decode(bytes: ByteArray): Pixels?
}

/**
 * VTOP text-captcha solver: a Kotlin port of `web/captcha_solver.py`, a single-layer
 * softmax classifier. The weights aren't in the repository; see docs/05-captcha-solver.md.
 *
 * Pipeline: the 200x40 captcha → per-pixel HSV saturation (0..255, rounded half-to-even
 * like numpy) → six fixed 22x24 cells → binarise each cell against its own mean →
 * logits = bits · W + b → argmax over the alphabet. Doubles throughout, so the result
 * matches the Python solver exactly (see CaptchaParityTest).
 */
class CaptchaSolver private constructor(
    private val alphabet: String,
    /** 528 x 32, row-major. */
    private val weights: DoubleArray,
    private val biases: DoubleArray,
) {
    /** Six characters; '?' where a cell lands on an output with no letter. */
    fun solve(px: Pixels): String {
        val img = if (px.width == W && px.height == H) px else resizeNearest(px)
        val sat = IntArray(W * H)
        for (i in sat.indices) {
            val c = img.argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            sat[i] = if (mx == 0) 0 else Math.rint((mx - mn) * 255.0 / mx).toInt()
        }
        val out = StringBuilder(6)
        val bits = DoubleArray(CELL_H * CELL_W)
        for (cell in 0 until 6) {
            val r0 = if (cell % 2 == 0) 8 else 13
            val c0 = (cell + 1) * 25 + 2
            var sum = 0.0
            for (y in 0 until CELL_H) for (x in 0 until CELL_W) sum += sat[(r0 + y) * W + c0 + x]
            val mean = sum / (CELL_H * CELL_W)
            for (y in 0 until CELL_H) for (x in 0 until CELL_W) {
                bits[y * CELL_W + x] = if (sat[(r0 + y) * W + c0 + x] > mean) 1.0 else 0.0
            }
            var best = 0
            var bestLogit = Double.NEGATIVE_INFINITY
            for (k in 0 until OUTPUTS) {
                var logit = biases[k]
                for (j in bits.indices) if (bits[j] != 0.0) logit += weights[j * OUTPUTS + k]
                if (logit > bestLogit) {
                    bestLogit = logit
                    best = k
                }
            }
            out.append(if (best < alphabet.length) alphabet[best] else '?')
        }
        return out.toString()
    }

    /** A solve worth submitting: six known characters. */
    fun isConfident(text: String) = text.length == 6 && '?' !in text

    private fun resizeNearest(px: Pixels): Pixels {
        val out = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            out[y * W + x] = px.argb[(y * px.height / H) * px.width + (x * px.width / W)]
        }
        return Pixels(W, H, out)
    }

    companion object {
        const val W = 200
        const val H = 40
        private const val CELL_H = 22
        private const val CELL_W = 24
        private const val OUTPUTS = 32

        // Absolute: R8 may move this class to another package in release builds.
        private const val MODEL = "/dev/medriid/davit/vtop/captcha_model.bin"

        /** Whether this build bundles the model. */
        fun available(): Boolean = CaptchaSolver::class.java.getResource(MODEL) != null

        /** Loads the bundled model (alphabet line, then 528x32 + 32 big-endian doubles). */
        fun load(): CaptchaSolver {
            val stream = CaptchaSolver::class.java.getResourceAsStream(MODEL) ?: throw ModelMissing()
            DataInputStream(stream.buffered()).use { input ->
                val alphabet = buildString {
                    while (true) {
                        val ch = input.readByte().toInt().toChar()
                        if (ch == '\n') break
                        append(ch)
                    }
                }
                val weights = DoubleArray(CELL_H * CELL_W * OUTPUTS) { input.readDouble() }
                val biases = DoubleArray(OUTPUTS) { input.readDouble() }
                return CaptchaSolver(alphabet, weights, biases)
            }
        }

        /** Reads every cell as "A". For tests that exercise the login rules, not the model. */
        internal fun constant(): CaptchaSolver =
            CaptchaSolver("ABCDEFGHJKLMNPQRSTUVWXYZ23456789", DoubleArray(CELL_H * CELL_W * OUTPUTS), DoubleArray(OUTPUTS).also { it[0] = 1.0 })
    }
}
