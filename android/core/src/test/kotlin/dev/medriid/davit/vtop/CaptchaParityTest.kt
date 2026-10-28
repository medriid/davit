package dev.medriid.davit.vtop

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import javax.imageio.ImageIO

/**
 * The Kotlin solver must give exactly what `web/captcha_solver.py` gives on the same
 * pixels. expected.txt was written by the Python solver; the images are generated
 * captchas (vtop-desktop's captcha-gen) plus coloured noise. PNG, so both sides decode
 * identical pixels.
 */
class CaptchaParityTest {
    private lateinit var solver: CaptchaSolver

    /** The weights aren't in the public repository; without them there's nothing to compare. */
    @Before
    fun model() {
        assumeTrue("captcha_model.bin not supplied (docs/05)", CaptchaSolver.available())
        solver = CaptchaSolver.load()
    }

    @Test
    fun matchesPythonSolver() {
        val expected = javaClass.getResource("/captcha/expected.txt")!!.readText()
            .lines().filter { it.isNotBlank() }.map { it.split(' ') }
        assertEquals(45, expected.size)
        val mismatches = expected.mapNotNull { (file, answer) ->
            val img = ImageIO.read(javaClass.getResource("/captcha/$file"))
            val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            val got = solver.solve(Pixels(img.width, img.height, argb))
            if (got == answer) null else "$file: python=$answer kotlin=$got"
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
