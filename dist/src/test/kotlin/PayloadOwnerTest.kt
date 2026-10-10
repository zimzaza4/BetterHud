import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.shader.ClipRegion
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.PayloadKind
import kr.toxicity.hud.shader.PayloadOwner
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.shader.ScrollConfig
import kr.toxicity.hud.shader.ScrollTween
import kr.toxicity.hud.shader.ShaderBody
import kr.toxicity.hud.shader.SmoothConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The payload carries one value, so "who owns it" has to be the same answer everywhere: the case body is
 * told which layout to decode, and the renderer packs a value into it. When those two decided separately,
 * an element asking for two features got the layout of one and the bytes of the other - a `clip-fill`
 * whose endpoints the client read as an angle, which spins the element instead of filling it.
 */
class PayloadOwnerTest {

    private fun shader(
        rotationDynamic: Boolean = false,
        positionDynamic: Boolean = false,
        smooth: SmoothConfig? = null,
        clip: ClipRegion? = null,
        scroll: ScrollConfig? = null
    ) = HudShader(
        gui = GuiLocation(0.0, 0.0),
        renderScale = RenderScale(PixelLocation.zero, RenderScale.Scale(1.0, 1.0, false)),
        layer = 0,
        outline = 0,
        opacity = 1.0,
        property = 0,
        rotationDynamic = rotationDynamic,
        positionDynamic = positionDynamic,
        clip = clip,
        smooth = smooth,
        scroll = scroll
    )

    private val region = ClipRegion(0.0, 0.0, 8.0, 120.0, axis = ClipRegion.AXIS_Y)
    private val narrow = SmoothConfig(4.0)
    private val rate = SmoothConfig(4.0, omegaScale = Math.toRadians(30.0))
    private val wide = SmoothConfig(ScrollTween.WINDOW + 20.0)
    private val scroll = ScrollConfig(ScrollConfig.AXIS_Y, 60.0)

    @Test
    fun testTheCaseBodyDecodesTheLayoutTheOwnerNames() {
        // Every combination the flags can make has to emit the kind the owner reports, or the renderer's
        // bytes and the shader's arithmetic disagree.
        val shaders = listOf(
            shader(),
            shader(rotationDynamic = true),
            shader(positionDynamic = true),
            shader(rotationDynamic = true, positionDynamic = true),
            shader(rotationDynamic = true, smooth = rate),
            shader(rotationDynamic = true, smooth = narrow),
            shader(rotationDynamic = true, smooth = wide),
            shader(smooth = narrow, clip = region),
            shader(smooth = narrow, clip = region, scroll = scroll),
            shader(rotationDynamic = true, smooth = narrow, clip = region),
            shader(rotationDynamic = true, smooth = rate, clip = region)
        )
        for (s in shaders) {
            // An element with nothing to transform emits no transform block at all, and so no layout.
            if (!s.transforms) continue
            assertTrue(
                ShaderBody.lines(s).any { it.contains("bhPayload = ${s.payloadKind}") },
                "the case body has to emit the owner's layout: ${s.payloadOwner}"
            )
        }
    }

    @Test
    fun testAFillAloneOwnsThePayload() {
        val only = shader(smooth = narrow, clip = region)
        assertEquals(PayloadOwner.FILL, only.payloadOwner)
        assertTrue(only.fillsRegion)
    }

    @Test
    fun testAStaticRotationLeavesTheFillItsPayload() {
        // The angle is baked into the case, so it costs nothing per update: this is the shape a rotating
        // bar with a moving boundary uses.
        val static = shader(rotationDynamic = false, smooth = narrow, clip = region)
            .copy(rotationDegree = 45.0)
        assertEquals(PayloadOwner.FILL, static.payloadOwner)
        assertTrue(static.fillsRegion, "a baked angle must not take the payload from the fill")
        assertTrue(
            ShaderBody.lines(static).any { it.contains("bhFillAxis = ${ClipRegion.AXIS_Y}") },
            "the boundary has to keep moving"
        )
    }

    @Test
    fun testAFillAndATurningElementShareTheMixedLayout() {
        // The pair no other layout has room for: the angle is stepped, the value keeps its tween, and the
        // region goes on moving - which is the whole reason the layout exists.
        val both = shader(rotationDynamic = true, smooth = narrow, clip = region)
        assertEquals(PayloadOwner.MIXED, both.payloadOwner)
        assertTrue(both.fillsRegion)
        val body = ShaderBody.lines(both)
        assertTrue(body.any { it.contains("bhPayload = ${PayloadKind.SMOOTH_MIXED}") })
        assertTrue(
            body.any { it.contains("bhFillAxis = ${ClipRegion.AXIS_Y}") },
            "the boundary has to keep moving"
        )
    }

    @Test
    fun testATurningElementWithoutAFillKeepsItsAngleTween() {
        // The mixed layout spends the angle's resolution on the value, so it is only worth taking when
        // there is a value to carry.
        assertEquals(
            PayloadOwner.ROTATION_TWEEN,
            shader(rotationDynamic = true, smooth = narrow).payloadOwner
        )
        assertEquals(PayloadOwner.ROTATION_RATE, shader(rotationDynamic = true, smooth = rate).payloadOwner)
    }

    @Test
    fun testTheWidestAngleTakesPrecedenceOverEveryOtherTween() {
        val wideFill = shader(rotationDynamic = true, smooth = wide, clip = region)
        assertEquals(PayloadOwner.ROTATION_WIDE, wideFill.payloadOwner)
        assertFalse(wideFill.fillsRegion)
        val body = ShaderBody.lines(wideFill)
        assertTrue(
            body.any { it.contains("bhValueRot = true") },
            "the eight bit payload drives the angle, so the case body has to say so"
        )
        assertTrue(
            body.any { it.contains("bhFillAxis = ${ClipRegion.NONE}") },
            "a fill which cannot be driven must not stay armed, or the element disappears"
        )
    }

    @Test
    fun testAScrollKeepsTheLayoutItHasAlwaysOwned() {
        val scrolled = shader(rotationDynamic = true, smooth = narrow, scroll = scroll)
        assertEquals(PayloadOwner.SCROLL, scrolled.payloadOwner)
        assertTrue(
            ShaderBody.lines(scrolled).any { it.contains("bhValueUse = ${ScrollConfig.AXIS_Y + 1}") },
            "the value has to drive the content, not the angle"
        )
    }

    @Test
    fun testTheLongestAngleRampBeatsAScroll() {
        // The eight bit payload is the only one whose tick reaches far enough for a long ramp, so it wins
        // even over a scroll - the scroll then steps, and the load time warning says so.
        val both = shader(rotationDynamic = true, smooth = wide, scroll = scroll)
        assertEquals(PayloadOwner.ROTATION_WIDE, both.payloadOwner)
        val body = ShaderBody.lines(both)
        assertTrue(body.any { it.contains("bhPayload = ${PayloadKind.SMOOTH_VALUE}") })
        assertTrue(
            body.any { it.contains("bhValueRot = true") },
            "the value carries the angle, so the case body has to say so"
        )
    }

    @Test
    fun testARateIsGivenUpForAFillItCannotCarry() {
        // A `smooth` with a rate and a filling region cannot both be carried: the mixed layout steps the
        // angle, so the rate is dropped without a warning (the angle keeps moving, just per update).
        val both = shader(rotationDynamic = true, smooth = rate, clip = region)
        assertEquals(PayloadOwner.MIXED, both.payloadOwner)
        assertTrue(both.fillsRegion, "the region moving is what the layout is for")
        assertTrue(
            ShaderBody.lines(both).any { it.contains("bhPayload = ${PayloadKind.SMOOTH_MIXED}") }
        )
    }

    @Test
    fun testTheUnitIsNotPartOfTheRegion() {
        // Two elements which differ only in how the value is read generate the same shader body, so the
        // unit must not split a case.
        assertEquals(ClipRegion.UNIT_PERCENT, ClipRegion.unitOf("percent"))
        assertEquals(ClipRegion.UNIT_PERCENT, ClipRegion.unitOf("%"))
        assertEquals(ClipRegion.UNIT_FRACTION, ClipRegion.unitOf("fraction"))
        assertEquals(ClipRegion.UNIT_AUTO, ClipRegion.unitOf("auto"))
        assertEquals(ClipRegion.UNIT_AUTO, ClipRegion.unitOf("nonsense"))
    }
}
