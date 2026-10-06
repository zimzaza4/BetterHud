import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.shader.ShaderBody
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Two elements which generate the same case body must share one case: the id they are told is written into
 * the ascent of their glyphs, and every id is one more compiled branch of the dispatch - of which there
 * are 255 at most, at an if-nesting depth a driver has to accept.
 */
class HudShaderCaseTest {

    private fun shader(
        gui: GuiLocation = GuiLocation(0.0, 0.0),
        scale: RenderScale = RenderScale(PixelLocation.zero, RenderScale.Scale(1.0, 1.0, false)),
        layer: Int = 0,
        outline: Int = 0,
        opacity: Double = 1.0,
        property: Int = 0,
        degree: Double = 0.0,
        rotationDynamic: Boolean = false,
        positionDynamic: Boolean = false,
        halfX: Double = 0.0,
        halfY: Double = 0.0,
        inRotatedSpace: Boolean = false,
        clipInner: Double = 0.0,
        clipOuter: Double = 0.0,
        clipOriginX: Double = 0.0,
        clipOriginY: Double = 0.0,
        hasClipOrigin: Boolean = false,
        anchorX: Double = 0.0,
        anchorY: Double = 0.0,
    ) = HudShader(
        gui,
        scale,
        layer,
        outline,
        opacity,
        property,
        degree,
        rotationDynamic,
        positionDynamic,
        halfX,
        halfY,
        inRotatedSpace,
        clipInner,
        clipOuter,
        clipOriginX,
        clipOriginY,
        hasClipOrigin,
        anchorX,
        anchorY,
    )

    private fun casesOf(shaders: List<HudShader>): Int {
        val cases = TreeMap<HudShader, Int>()
        shaders.forEach { cases[it] = cases.size }
        return cases.size
    }

    @Test
    fun testSizedElementsWhichNeverTurnShareOneCase() {
        // an image element carries its own size, and only the pivot half of it reaches the shader
        val sizes = listOf(2.0, 5.5, 11.0, 16.0, 32.0, 57.0, 64.0, 128.0)
        val shaders = sizes.map { shader(halfX = it, halfY = it * 1.5) }
        assertEquals(1, casesOf(shaders))
        assertEquals(ShaderBody.lines(shaders.first()), ShaderBody.lines(shaders.last()))
    }

    @Test
    fun testTurningElementStillSplitsByItsHalf() {
        val sizes = listOf(2.0, 5.5, 11.0, 16.0, 32.0, 57.0, 64.0, 128.0)
        val shaders = sizes.map { shader(degree = 25.0, halfX = it, halfY = it * 1.5) }
        assertEquals(sizes.size, casesOf(shaders))
    }

    @Test
    fun testPivotHalfAndAnchorOnlyMatterWhileTurning() {
        assertEquals(0, shader().compareTo(shader(halfX = 300.0, halfY = 200.0, anchorX = -20.0, anchorY = 4.0)))
        assertNotEquals(0, shader(degree = 25.0).compareTo(shader(degree = 25.0, halfX = 300.0)))
        assertNotEquals(0, shader(degree = 25.0).compareTo(shader(degree = 25.0, anchorX = -20.0)))
    }

    @Test
    fun testClipFieldsOnlyMatterWhileClipping() {
        // clip-inner/clip-origin without a clip radius are read by nothing
        assertEquals(0, shader().compareTo(shader(clipInner = 5.0)))
        assertEquals(0, shader().compareTo(shader(clipOriginX = 9.0, clipOriginY = 9.0)))
        // ... and the origin is the element's own center unless it was given
        assertEquals(0, shader(clipOuter = 8.0).compareTo(shader(clipOuter = 8.0, clipOriginX = 9.0, clipOriginY = 9.0)))
        assertNotEquals(0, shader(clipOuter = 8.0, clipInner = 2.0).compareTo(shader(clipOuter = 8.0)))
        assertNotEquals(
            0,
            shader(clipOuter = 8.0, clipOriginX = 9.0, clipOriginY = 9.0, hasClipOrigin = true)
                .compareTo(shader(clipOuter = 8.0, clipOriginX = 1.0, clipOriginY = 1.0, hasClipOrigin = true))
        )
    }

    @Test
    fun testOutlineValueIsBoolean() {
        // the body writes `outline = true` and nothing else
        assertEquals(0, shader(outline = 1).compareTo(shader(outline = 99)))
        assertNotEquals(0, shader(outline = 0).compareTo(shader(outline = 1)))
    }

    @Test
    fun testPayloadKindStillSplits() {
        val kinds = listOf(
            shader(),
            shader(rotationDynamic = true),
            shader(positionDynamic = true),
            shader(rotationDynamic = true, positionDynamic = true)
        )
        assertEquals(4, casesOf(kinds))
    }

    @Test
    fun testElementPositionIsIgnoredWhileTheAnchorIsUnread() {
        // The one deliberate exception to the invariant above: at scale 1 an element's own position only
        // reaches the body through bhAnchorX/Y, and nothing reads those yet (text.vsh takes the pivot from
        // the glyph center). Should the shader ever start reading the anchor, the offset has to enter
        // RenderScale.compareTo again, or the second element would turn around the first one's centre.
        val one = shader(scale = RenderScale(PixelLocation(3, 4, 1.0), RenderScale.Scale(1.0, 1.0, false)))
        val other = shader(scale = RenderScale(PixelLocation(90, -12, 1.0), RenderScale.Scale(1.0, 1.0, false)))
        assertEquals(0, one.compareTo(other))
        assertNotEquals(ShaderBody.lines(one), ShaderBody.lines(other))
    }

    @Test
    fun testIdentityFollowsTheGeneratedBody() {
        val variants = listOf(
            shader(),
            shader(gui = GuiLocation(50.0, 50.0)),
            shader(layer = 1),
            shader(outline = 1),
            shader(outline = 99),
            shader(opacity = 0.5),
            shader(property = 3),
            shader(scale = RenderScale(PixelLocation.zero, RenderScale.Scale(2.0, 1.0, false))),
            shader(scale = RenderScale(PixelLocation.zero, RenderScale.Scale(2.0, 1.0, true))),
            shader(halfX = 32.0, halfY = 32.0),
            shader(anchorX = 5.0, anchorY = 7.0),
            shader(clipInner = 5.0),
            shader(clipOriginX = 9.0, clipOriginY = 9.0),
            shader(inRotatedSpace = true),
            shader(degree = 25.0),
            shader(degree = 25.0, halfX = 32.0, halfY = 32.0),
            shader(degree = 25.0, anchorX = 5.0, anchorY = 7.0),
            shader(degree = 25.0, inRotatedSpace = true),
            shader(rotationDynamic = true),
            shader(positionDynamic = true),
            shader(clipOuter = 8.0),
            shader(clipOuter = 8.0, clipInner = 2.0),
            shader(clipOuter = 8.0, clipOriginX = 9.0, clipOriginY = 9.0),
            shader(clipOuter = 8.0, clipOriginX = 9.0, clipOriginY = 9.0, hasClipOrigin = true),
            shader(clipOuter = 8.0, clipOriginX = 1.0, clipOriginY = 1.0, hasClipOrigin = true),
            shader(clipOuter = 8.0, degree = 25.0, anchorX = -3.0, anchorY = 2.0)
        )
        variants.forEach { one ->
            variants.forEach { other ->
                assertEquals(
                    one.compareTo(other) == 0,
                    ShaderBody.lines(one) == ShaderBody.lines(other),
                    "identity has to follow the generated body:\n" +
                        "  ${one.renderScale.scale} / ${other.renderScale.scale}\n" +
                        "  ${ShaderBody.lines(one)}\n" +
                        "  ${ShaderBody.lines(other)}"
                )
            }
        }
    }
}
