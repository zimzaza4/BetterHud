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

    private val overlays = listOf(
        "betterhud_1_21_4",
        "betterhud_1_21_6",
        "betterhud_26_1",
        "betterhud_26_2",
        "betterhud_26_3"
    )

    private val vertexIdAllowedOverlays = setOf(
        "betterhud_1_21_4",
        "betterhud_1_21_6",
        "betterhud_26_1"
    )

    private fun shaderDir(): File =
        listOf("../common-resources/shaders", "common-resources/shaders")
            .map(::File)
            .first { it.isDirectory }

    private fun source(overlay: String, name: String): String =
        File(shaderDir(), "$overlay/$name").readText()

    @Test
    fun testNoFragmentScreenCoordinate() {
        for (overlay in overlays) {
            for (name in listOf("text.vsh", "text.fsh")) {
                // `gl_FragCoord.z` is vanilla 26.3 OIT depth only; `.xy` or bare `gl_FragCoord` is unsafe under Vulkan.
                val stripped = code(overlay, name).replace("gl_FragCoord.z", "")
                assertFalse(
                    stripped.contains("gl_FragCoord"),
                    "$overlay/$name must not read gl_FragCoord (the Vulkan backend may flip its Y axis)"
                )
            }
        }
    }

    /** The source without its `//` comments, so prose may still name what the code avoids. */
    private fun code(overlay: String, name: String): String = source(overlay, name).lineSequence()
        .map { it.substringBefore("//") }
        .joinToString("\n")

    @Test
    fun testTheExtrapolatedAgeKeepsOnlyTheFraction() {
        for (overlay in overlays) {
            val shader = code(overlay, "text.vsh")
            assertTrue(
                shader.contains("fract(bhAgeRaw)"),
                "$overlay/text.vsh: the payload-5 age must keep only the fraction of the counter difference"
            )
            assertFalse(
                shader.contains("max(bhAgeRaw"),
                "$overlay/text.vsh: the payload-5 age must not keep the whole difference (it carries the clock offset)"
            )
            assertTrue(
                shader.contains("bhAge(bhBits & 15, 16.0)"),
                "$overlay/text.vsh: the two endpoint ramp must take its phase from the tick the update was sent for"
            )
            assertFalse(
                shader.contains("fract(GameTime * 24000"),
                "$overlay/text.vsh: no payload may phase itself on the client's own tick boundary"
            )
        }
    }

    @Test
    fun testVertexIndexOnlyInPre26_2Overlays() {
        for (overlay in overlays) {
            for (name in listOf("text.vsh", "text.fsh")) {
                val hasVertexId = code(overlay, name).contains("gl_VertexID")
                if (overlay in vertexIdAllowedOverlays) {
                    if (name == "text.vsh") {
                        assertTrue(hasVertexId, "$overlay/$name should use gl_VertexID")
                    }
                } else {
                    assertFalse(hasVertexId, "$overlay/$name must not use gl_VertexID on 26.2+ / Vulkan")
                }
            }
        }
    }

    @Test
    fun testTimeModuliDivide24000() {
        for (overlay in overlays) {
            val calls = Regex("""bhAge\([^,]+,([^)]+)\)""")
                .findAll(source(overlay, "text.vsh"))
                .map { it.groupValues[1].trim().removeSuffix(".0") }
                .mapNotNull { it.toDoubleOrNull()?.toInt() }
                .toSet()
            assertTrue(calls.isNotEmpty(), "$overlay: expected the payload decoders to read the game clock")
            calls.forEach {
                assertEquals(0, 24000 % it, "$overlay: a tick modulus of $it does not divide 24000")
            }
        }
    }

    @Test
    fun testDefineBlocksAreBalanced() {
        for (overlay in overlays) {
            for (name in listOf("text.vsh", "text.fsh")) {
                var depth = 0
                source(overlay, name).lines().forEach {
                    val trimmed = it.trimStart()
                    if (trimmed.startsWith("#if")) depth++
                    else if (trimmed.startsWith("#endif")) depth--
                    assertTrue(depth >= 0, "$overlay/$name has an #endif without an #if")
                }
                assertEquals(0, depth, "$overlay/$name has an unbalanced #if")
            }
        }
    }
}
