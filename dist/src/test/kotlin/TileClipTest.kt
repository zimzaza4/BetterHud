import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.shader.ClipRegion
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.shader.ScrollConfig
import kr.toxicity.hud.shader.ShaderBody
import kr.toxicity.hud.shader.SmoothConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cut image is drawn as one glyph per tile, and each tile gets its own copy of the shader with only
 * the pivot corrected (`ImageTiles.shaderOf`). Everything else has to travel with that copy - twice
 * already the region was lost that way, and the symptom was one window per tile instead of one window
 * across the whole picture.
 */
class TileClipTest {

    private fun shader(
        clip: ClipRegion? = null,
        smooth: SmoothConfig? = null,
        scroll: ScrollConfig? = null
    ) = HudShader(
        GuiLocation(0.0, 0.0),
        RenderScale(PixelLocation.zero, RenderScale.Scale(1.0, 1.0, false)),
        0,
        0,
        1.0,
        0,
        0.0,
        false,
        false,
        0.0,
        0.0,
        false,
        0.0,
        0.0,
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
    fun testATileCopyKeepsTheRegionAndTheScroll() {
        val clip = ClipRegion(-175.5, -7.0, 351.0, 14.0, soft = 1.0, followsContent = false)
        val scroll = ScrollConfig(ScrollConfig.AXIS_X, 360.0)
        val smooth = SmoothConfig(duration = 3.0)
        val base = shader(clip = clip, smooth = smooth, scroll = scroll)

        // What `ImageTiles.shaderOf` does for a tile: copy, then restate the half size and the anchor.
        val tile = base.copy(rotationHalfX = 60.0, rotationHalfY = 7.0, rotationAnchorX = 115.5, rotationAnchorY = 0.0)

        assertEquals(clip, tile.clip)
        assertEquals(scroll, tile.scroll)
        assertEquals(smooth, tile.smooth)
        assertTrue(tile.pivotsAroundElementCenter, "a tile has to keep pivoting around the element's centre")
        assertEquals(base.scrollSmooth, tile.scrollSmooth)

        // The region lines of the generated body must be identical, or the tiles would clip differently.
        fun regionLines(shader: HudShader) =
            ShaderBody.lines(shader).filter { it.contains("bhFill") || it.contains("bhValue") }

        assertEquals(regionLines(base), regionLines(tile))
    }
}
