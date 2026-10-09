import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules which keep one shader source working from 1.21.4 through 26.2, the Vulkan renderer
 * included:
 *
 * - no `gl_VertexID`: 26.2 stages every GUI draw in one buffer and issues each with a baseVertex
 *   offset, so the index inside the draw is not the corner of the glyph (the version guarded branch
 *   is the exception, and only reaches 26.1 and older);
 * - no `gl_FragCoord`: the Vulkan backend may flip the Y axis, so screen coordinates cannot be trusted
 *   (clip regions are measured in the element's own pixels instead);
 * - every modulus divides 24000: `GameTime * 24000` is the world time of the day, so any other divisor
 *   loses the remainder when the day counter wraps.
 */
class ShaderCompatTest {

    private fun source(name: String): String =
        listOf("../common-resources/$name", "common-resources/$name")
            .map(::File)
            .first { it.isFile }
            .readText()

    @Test
    fun testNoFragmentCoordinate() {
        for (name in listOf("text.vsh", "text.fsh")) {
            assertFalse(
                source(name).contains("gl_FragCoord"),
                "$name must not read gl_FragCoord (the Vulkan backend may flip its Y axis)"
            )
        }
    }

    /** The source without its `//` comments, so prose may still name what the code avoids. */
    private fun code(name: String): String = source(name).lineSequence()
        .map { it.substringBefore("//") }
        .joinToString("\n")

    @Test
    fun testTheExtrapolatedAgeKeepsOnlyTheFraction() {
        // `bhAge` is a difference of two whole tick counters, so it carries the client/server clock offset.
        // Kept, the client extrapolates for `offset + fraction` ticks and sails past a stop. The fraction is
        // the only legal age, and it must not be swapped for the client's own tick boundary either.
        val shader = code("text.vsh")
        assertTrue(
            shader.contains("fract(bhAgeRaw)"),
            "the payload-5 age must keep only the fraction of the counter difference"
        )
        assertFalse(
            shader.contains("max(bhAgeRaw"),
            "the payload-5 age must not keep the whole difference (it carries the clock offset)"
        )
        assertTrue(
            shader.contains("bhAge(bhBits & 15, 16.0)"),
            "the two endpoint ramp must take its phase from the tick the update was sent for"
        )
        assertFalse(
            shader.contains("fract(GameTime * 24000"),
            "no payload may phase itself on the client's own tick boundary"
        )
    }

    @Test
    fun testVertexIndexOnlyInsideAVersionGuard() {
        for (name in listOf("text.vsh", "text.fsh")) {
            val guards = ArrayDeque<String>()
            var inBlockComment = false
            source(name).lines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                // The header comments explain the 26.2 corner problem and name the builtin they avoid.
                if (inBlockComment) {
                    if (trimmed.contains("*/")) inBlockComment = false
                    return@forEachIndexed
                }
                if (trimmed.startsWith("/*")) {
                    inBlockComment = !trimmed.contains("*/")
                    return@forEachIndexed
                }
                if (trimmed.startsWith("//")) return@forEachIndexed
                if (trimmed.startsWith("#if")) guards.addLast(trimmed)
                else if (trimmed.startsWith("#endif")) guards.removeLastOrNull()
                if (line.contains("gl_VertexID")) {
                    val guard = guards.lastOrNull() ?: ""
                    assertTrue(
                        guard.contains("SHADER_VERSION"),
                        "$name:${index + 1} uses gl_VertexID outside a SHADER_VERSION branch (guard: '$guard')"
                    )
                }
            }
        }
    }

    @Test
    fun testTimeModuliDivide24000() {
        val calls = Regex("""bhAge\([^,]+,([^)]+)\)""")
            .findAll(source("text.vsh"))
            .map { it.groupValues[1].trim().removeSuffix(".0") }
            .mapNotNull { it.toDoubleOrNull()?.toInt() }
            .toSet()
        assertTrue(calls.isNotEmpty(), "expected the payload decoders to read the game clock")
        calls.forEach {
            assertEquals(0, 24000 % it, "a tick modulus of $it does not divide 24000")
        }
    }

    @Test
    fun testDefineBlocksAreBalanced() {
        for (name in listOf("text.vsh", "text.fsh")) {
            var depth = 0
            source(name).lines().forEach {
                val trimmed = it.trimStart()
                if (trimmed.startsWith("#if")) depth++
                else if (trimmed.startsWith("#endif")) depth--
                assertTrue(depth >= 0, "$name has an #endif without an #if")
            }
            assertEquals(0, depth, "$name has an unbalanced #if")
        }
    }
}
