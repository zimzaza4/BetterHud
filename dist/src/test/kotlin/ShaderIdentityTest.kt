import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.shader.ClipRegion
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.PayloadKind
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.shader.ShaderBody
import kr.toxicity.hud.shader.ScrollConfig
import kr.toxicity.hud.shader.SmoothConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The new keys are opt in: an element which does not use them has to generate the very same case body
 * as before, or every existing config would be repacked into different cases.
 */
class ShaderIdentityTest {

    private fun shader(
        clip: ClipRegion? = null,
        smooth: SmoothConfig? = null,
        rotationDynamic: Boolean = false,
        positionDynamic: Boolean = false,
        clipOuter: Double = 0.0,
        scroll: ScrollConfig? = null
    ) = HudShader(
        GuiLocation(0.0, 0.0),
        RenderScale(PixelLocation.zero, RenderScale.Scale(1.0, 1.0, false)),
        0,
        0,
        1.0,
        0,
        0.0,
        rotationDynamic,
        positionDynamic,
        0.0,
        0.0,
        false,
        0.0,
        clipOuter,
        0.0,
        0.0,
        false,
        0.0,
        0.0,
        clip = clip,
        smooth = smooth,
        scroll = scroll
    )

    @Test
    fun testAnElementWithoutTheNewKeysEmitsNothingNew() {
        val body = ShaderBody.lines(shader())
        assertEquals(0, body.count { it.contains("bhFill") }, "a plain element must not emit a clip region")
        assertEquals(0, body.count { it.contains("bhSmooth") }, "a plain element must not emit tween settings")
        assertEquals(0, body.count { it.contains("bhOmegaScale") }, "a plain element must not emit a rotation rate")
    }

    @Test
    fun testTheNewKeysReachTheBody() {
        val body = ShaderBody.lines(
            shader(
                clip = ClipRegion(0.0, 0.0, 16.0, 8.0, radius = 2.0, soft = 1.0, axis = ClipRegion.AXIS_X),
                smooth = SmoothConfig(duration = 6.0, easing = SmoothConfig.EASE_OUT)
            )
        )
        assertTrue(body.any { it.contains("bhFillOn = true") })
        assertTrue(body.any { it.contains("bhFillMin = vec2(0.0, 0.0)") })
        assertTrue(body.any { it.contains("bhFillMax = vec2(16.0, 8.0)") })
        assertTrue(body.any { it.contains("bhFillRadius = 2.0") })
        assertTrue(body.any { it.contains("bhFillSoft = 1.0") })
        assertTrue(body.any { it.contains("bhFillAxis = 0") })
        assertTrue(body.any { it.contains("bhSmoothDur = 6.0") })
        assertTrue(body.any { it.contains("bhSmoothEase = 2.0") })
        // A clip region is a transform: it needs the pivot the local coordinate is measured from.
        assertTrue(body.any { it.contains("bhRotOn = true") })
    }

    @Test
    fun testTheNewKeysSplitTheIdentity() {
        val clip = ClipRegion(0.0, 0.0, 8.0, 8.0, axis = ClipRegion.AXIS_Y)
        assertNotEquals(0, shader().compareTo(shader(clip = clip)))
        assertNotEquals(0, shader().compareTo(shader(smooth = SmoothConfig(6.0))))
        assertNotEquals(0, shader(clip = clip).compareTo(shader(clip = clip.copy(soft = 1.0))))
        assertEquals(0, shader(clip = clip).compareTo(shader(clip = clip.copy())))
    }

    @Test
    fun testWhichPayloadAnElementNeeds() {
        val region = ClipRegion(0.0, 0.0, 8.0, 8.0, axis = ClipRegion.AXIS_X)
        val tween = SmoothConfig(duration = 6.0)
        // A filling region: the value is the payload (`PayloadKind.SMOOTH_VALUE`).
        assertTrue(shader(clip = region, smooth = tween).valueSmooth)
        assertEquals(
            PayloadKind.SMOOTH_VALUE,
            PayloadKind.of(false, false, shader(clip = region, smooth = tween).rotationSmooth, true)
        )
        // A static region only clips: it does not need one.
        assertFalse(shader(clip = region).valueSmooth)
        // A tweened rotation owns the payload, and a region which does not fill never competes for it.
        val rotating = shader(smooth = SmoothConfig(6.0, omegaScale = 0.1), rotationDynamic = true)
        assertTrue(rotating.rotationSmooth)
        assertEquals(
            PayloadKind.SMOOTH_ROTATION,
            PayloadKind.of(true, false, rotating.rotationSmooth, false)
        )
    }

    @Test
    fun testEveryFillShapeReachesTheBody() {
        val tween = SmoothConfig(duration = 6.0)
        // 3/4 are the symmetric ones, 5 the pie; all of them fill, so all of them need the payload.
        for (axis in listOf(
            ClipRegion.AXIS_X,
            ClipRegion.AXIS_Y,
            ClipRegion.AXIS_RADIAL,
            ClipRegion.AXIS_CENTER_X,
            ClipRegion.AXIS_CENTER_Y,
            ClipRegion.AXIS_PIE
        )) {
            val shader = shader(clip = ClipRegion(0.0, 0.0, 8.0, 8.0, axis = axis), smooth = tween)
            assertTrue(shader.valueSmooth, "axis $axis should fill and therefore take the payload")
            assertTrue(
                ShaderBody.lines(shader).any { it.contains("bhFillAxis = $axis") },
                "axis $axis should be written into the case body"
            )
        }
        // A region which only clips never asks for a payload.
        assertFalse(shader(clip = ClipRegion(0.0, 0.0, 8.0, 8.0)).valueSmooth)
    }

    @Test
    fun testAClipRegionPivotsAroundTheElementCentre() {
        // A tile of a cut image has to be told where the element's centre is, or each tile measures the
        // region from its own centre - which is what turns one window into one window per tile.
        assertTrue(shader(clip = ClipRegion(-180.0, -8.0, 360.0, 16.0)).pivotsAroundElementCenter)
        assertTrue(shader(scroll = ScrollConfig(ScrollConfig.AXIS_X, 360.0)).pivotsAroundElementCenter)
        assertFalse(shader().pivotsAroundElementCenter)
    }

    @Test
    fun testARegionWithoutAFillStillClips() {
        // `clip-rect` without `clip-fill` is a fixed window: it must reach the body with no axis, which
        // the shader reads as "leave the rectangle where it is", and it must not ask for a payload.
        val region = ClipRegion(-180.0, -8.0, 360.0, 16.0)
        val shader = shader(clip = region, scroll = ScrollConfig(ScrollConfig.AXIS_X, 360.0))
        assertFalse(shader.valueSmooth)
        val body = ShaderBody.lines(shader)
        assertTrue(body.any { it.contains("bhFillOn = true") })
        assertTrue(body.any { it.contains("bhFillAxis = -1") })
        assertTrue(body.any { it.contains("bhFillMin = vec2(-180.0, -8.0)") })
        assertTrue(body.any { it.contains("bhFillMax = vec2(180.0, 8.0)") })
    }

    @Test
    fun testAScrollUsesTheRateCarryingPayload() {
        val scrolling = shader(
            scroll = ScrollConfig(ScrollConfig.AXIS_X, 256.0),
            smooth = SmoothConfig(duration = 2.0)
        )
        assertTrue(scrolling.scrollSmooth)
        // A scroll and a tweened rotation share the 11 bit value plus 8 bit rate layout, which is the
        // precise one; the shader tells them apart by `bhValueUse`.
        assertEquals(
            PayloadKind.SMOOTH_ROTATION,
            PayloadKind.of(false, false, scrolling.rotationSmooth || scrolling.scrollSmooth, scrolling.valueSmooth)
        )
        val body = ShaderBody.lines(scrolling)
        assertTrue(body.any { it.contains("bhValueUse = 1") })
        assertTrue(body.any { it.contains("bhValueScale = 256.0") })
        assertNotEquals(0, shader().compareTo(scrolling))
    }

    @Test
    fun testTheRegionDecidesWhetherItFollowsTheContent() {
        val region = ClipRegion(0.0, 0.0, 8.0, 8.0)
        // A fixed window is the default - the rectangle stays where the element was placed, which is what
        // a scrolling strip behind a compass window wants, so it is the one that is written out.
        assertEquals(1, ShaderBody.lines(shader(clip = region)).count { it.contains("bhFillFollow = false") })
        // `clip-follow: content` turns the rectangle into a stencil on the picture and stays implicit.
        val following = shader(clip = region.copy(followsContent = true))
        assertEquals(0, ShaderBody.lines(following).count { it.contains("bhFillFollow") })
        assertNotEquals(0, shader(clip = region).compareTo(following))
    }
}
