import kr.toxicity.hud.shader.ShaderDispatch
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dispatch of `text.vsh` has to stay flat: NVIDIA's Cg frontend lowers a `switch` into an if/else
 * chain nested once per case and refuses more than 64 levels, so before this the whole text pipeline
 * failed to link for any pack of ~62 elements or more (and the client then drops every resource pack).
 */
class ShaderDispatchTest {

    private fun bodies(count: Int) = (1..count).map { listOf("body$it;") }

    /** The deepest `if` nesting of the emitted lines; `} else {` stays on the same level. */
    private fun depth(lines: List<String>): Int {
        var current = 0
        var max = 0
        lines.forEach {
            when {
                it.startsWith("if (") -> {
                    current++
                    if (current > max) max = current
                }

                it == "}" -> current--
            }
        }
        return max
    }

    private fun flat(lines: List<String>): List<String> {
        val start = lines.indexOf("#ifdef ${ShaderDispatch.MARKER}") + 1
        val end = lines.indexOf("#else")
        assertTrue(start in 1..lines.size && end > start, "the flat form is missing")
        return lines.subList(start, end)
    }

    private fun chained(lines: List<String>): List<String> {
        val start = lines.indexOf("#else") + 1
        val end = lines.indexOf("#endif")
        assertTrue(start in 1..lines.size && end > start, "the switch form is missing")
        return lines.subList(start, end)
    }

    @Test
    fun testNothingToDispatch() {
        assertEquals(emptyList(), ShaderDispatch.lines(emptyList()))
    }

    @Test
    fun testBothFormsAreWrapped() {
        val lines = ShaderDispatch.lines(bodies(3))
        assertEquals("#ifdef ${ShaderDispatch.MARKER}", lines.first())
        assertEquals("#endif", lines.last())
        assertTrue(lines.contains("#else"))
        // the flat form must not contain a single case label: the bundled shader has no switch
        assertFalse(flat(lines).any { it.startsWith("case ") })
        // ... while the fallback form is nothing but case labels
        assertEquals(3, chained(lines).count { it.startsWith("case ") })
        assertEquals(3, chained(lines).count { it == "    break;" })
    }

    @Test
    fun testEveryElementIsReachableExactlyOnce() {
        (1..255).forEach { count ->
            val all = bodies(count)
            val lines = ShaderDispatch.lines(all)
            listOf("flat" to flat(lines), "chain" to chained(lines)).forEach { (form, part) ->
                val found = part.filter { it.startsWith("body") }
                assertEquals(all.flatten().filter { it.startsWith("body") }, found, "$form form lost a body at $count")
                assertEquals(
                    (1..count).map { "body$it;" },
                    found,
                    "$form form does not visit the ids in order at $count"
                )
            }
        }
    }

    @Test
    fun testShapeOfTheTree() {
        // what three elements look like: one guard, then one `if` per halving until a range holds one id
        assertEquals(
            listOf(
                "#ifdef BH_FLAT_LAYOUT",
                "if (id >= 1 && id <= 3) {",
                "if (id <= 2) {",
                "if (id <= 1) {",
                "body1;",
                "} else {",
                "body2;",
                "}",
                "} else {",
                "body3;",
                "}",
                "}",
                "#else",
                "case 1:",
                "body1;",
                "    break;",
                "case 2:",
                "body2;",
                "    break;",
                "case 3:",
                "body3;",
                "    break;",
                "#endif"
            ),
            ShaderDispatch.lines(bodies(3))
        )
    }

    @Test
    fun testFlatDepthIsLogarithmic() {
        // 1 guard + one level per halving: ceil(log2(n)) instead of n
        (1..255).forEach { count ->
            val expected = if (count == 1) 1 else 1 + ceil(log2(count.toDouble())).toInt()
            assertEquals(expected, depth(flat(ShaderDispatch.lines(bodies(count)))), "depth at $count elements")
        }
    }

    @Test
    fun testFlatDepthStaysFarBelowTheDriverLimit() {
        // the two `if`s above the dispatch (the screen-bottom test and the id marker) are not part of it
        (1..255).forEach { count ->
            assertTrue(
                depth(flat(ShaderDispatch.lines(bodies(count)))) + 2 <= 12,
                "the dispatch of $count elements needs more nesting than the driver allows"
            )
        }
    }

    @Test
    fun testTheSwitchFormIsWhatTheDriverChains() {
        // The source of the switch form has no `if` of its own - the nesting comes from the driver,
        // which lowers every case into one more level of if/else. Measured on a real client: a pack of
        // 68 elements produced 70 nested ifs in the driver's assembly dump, and its frontend refused
        // the program when it reached level 65.
        val lines = chained(ShaderDispatch.lines(bodies(68)))
        assertEquals(0, depth(lines))
        assertEquals(68, lines.count { it.startsWith("case ") })
    }

    @Test
    fun testOrderOfTheCasesIsKept() {
        val lines = ShaderDispatch.lines(listOf(listOf("first;"), listOf("second;"), listOf("third;")))
        fun bodiesOf(part: List<String>) = part.filter { it.endsWith(";") && !it.contains("break") }
        assertEquals(listOf("first;", "second;", "third;"), bodiesOf(flat(lines)))
        assertEquals(listOf("first;", "second;", "third;"), bodiesOf(chained(lines)))
    }

    @Test
    fun testBundledShaderMatchesTheDispatch() {
        // The two halves of the trick live in different modules: the generator emits both forms, and the
        // bundled shader decides which one survives. A copy of the shader left with a `switch` while the
        // generator emits a tree (or the other way round) breaks the whole text pipeline, so pin it.
        val file = listOf("../common-resources/text.vsh", "common-resources/text.vsh").map(::File).firstOrNull { it.isFile }
        assumeTrue(file != null, "the test has to run from the module directory to find the bundled shader")
        val shader = file!!.readText()
        assertTrue(
            shader.contains("#define ${ShaderDispatch.MARKER}"),
            "the bundled text.vsh has to define ${ShaderDispatch.MARKER}"
        )
        assertFalse(shader.contains("switch (id)"), "the bundled text.vsh still wraps the cases in a switch")
        assertTrue(shader.contains("#CreateLayout"), "the bundled text.vsh lost the layout tag")
    }
}
