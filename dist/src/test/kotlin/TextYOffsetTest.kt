import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.shader.ShaderBody
import kr.toxicity.hud.shader.ShaderGroup
import kr.toxicity.hud.util.SPACE_KEY
import kr.toxicity.hud.util.TEXT_Y_MAX
import kr.toxicity.hud.util.TEXT_Y_MIN
import kr.toxicity.hud.util.decodeTextYBits
import kr.toxicity.hud.util.encodeTextY
import kr.toxicity.hud.util.encodeTextYBits
import kr.toxicity.hud.util.textYBucket
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextYOffsetTest {

    private fun baseShader(
        scaleY: Double = 1.0,
        staticScale: Boolean = false,
        rotationDynamic: Boolean = false
    ) = HudShader(
        GuiLocation(50.0, 50.0),
        RenderScale(PixelLocation.zero, RenderScale.Scale(1.0, scaleY, staticScale)),
        0,
        0,
        1.0,
        0,
        rotationDynamic = rotationDynamic
    )

    @Test
    fun testAllOffsetsRoundTripAcrossRepresentativeColors() {
        val colors = listOf(0xFFFFFF, 0x000000, 0xFF0000, 0x00FF00, 0x3C8527, 0x1E1E1F, 0x7A8B9C)
        for (rgb in colors) {
            for (dy in TEXT_Y_MIN..TEXT_Y_MAX) {
                val encoded = rgb.encodeTextYBits(dy)
                assertEquals(dy, decodeTextYBits(encoded), "rgb=${rgb.toString(16)} dy=$dy")
                val dr = abs(((encoded ushr 16) and 0xFF) - ((rgb ushr 16) and 0xFF))
                val dg = abs(((encoded ushr 8) and 0xFF) - ((rgb ushr 8) and 0xFF))
                val db = abs((encoded and 0xFF) - (rgb and 0xFF))
                assertTrue(dr <= 7 && dg <= 7 && db <= 7, "channel drift too large: ($dr,$dg,$db)")
            }
        }
    }

    @Test
    fun testBucketCoversArbitraryYCoordinatesWithoutError() {
        for (y in TEXT_Y_MIN..TEXT_Y_MAX) {
            assertEquals(0, textYBucket(y))
        }
        for (y in -10_000..10_000 step 17) {
            val bucket = textYBucket(y)
            val dy = y - bucket
            assertTrue(dy in TEXT_Y_MIN..TEXT_Y_MAX, "y=$y bucket=$bucket dy=$dy out of range")
            assertEquals(y, bucket + decodeTextYBits(0xFFFFFF.encodeTextYBits(dy)))
        }
    }

    @Test
    fun testComponentTreeEncodesChildrenShadowsAndSkipsSpaces() {
        val component = Component.text("HP ")
            .shadowColor(ShadowColor.shadowColor(0x80000000.toInt()))
            .append(Component.text("100", NamedTextColor.RED))
            .append(Component.text("\uD800").font(SPACE_KEY))
        val encoded = component.encodeTextY(-38)

        assertEquals(-38, decodeTextYBits(encoded.color()!!.value()))
        val shadow = encoded.shadowColor()!!
        assertEquals(0x80, shadow.value() ushr 24)
        assertEquals(-38, decodeTextYBits(shadow.value()))

        val numberChild = encoded.children()[0]
        assertEquals(-38, decodeTextYBits(numberChild.color()!!.value()))
        assertTrue((numberChild.color()!!.red()) > 200)

        val spaceChild = encoded.children()[1]
        assertNull(spaceChild.color())
    }

    @Test
    fun testShaderSupportAndIdentitySplitting() {
        val plain = baseShader()
        val withY = plain.withTextYOffset()
        assertTrue(plain.supportsTextYOffset)
        assertFalse(plain.textYOffset)
        assertTrue(withY.textYOffset)
        assertNotEquals(plain, withY)
        assertFalse(ShaderBody.lines(plain).any { it.contains("bhTextY") })
        assertTrue(ShaderBody.lines(withY).any { it.contains("bhTextY = true;") })

        val scaled = baseShader(scaleY = 1.5)
        assertFalse(scaled.supportsTextYOffset)
        assertFalse(scaled.withTextYOffset().textYOffset)

        val staticScaled = baseShader(staticScale = true)
        assertFalse(staticScaled.supportsTextYOffset)
        assertFalse(staticScaled.withTextYOffset().textYOffset)

        val transforming = baseShader(rotationDynamic = true)
        assertFalse(transforming.supportsTextYOffset)
        assertFalse(transforming.withTextYOffset().textYOffset)
    }

    @Test
    fun testMultiLineAndMultiYShareSingleShaderGroupWithinBucket() {
        val shader = baseShader().withTextYOffset()
        val ys = listOf(-256, -40, 0, 10, 20, 30, 255)
        val groups = ys.map { y ->
            ShaderGroup(shader, "hud_font", textYBucket(y))
        }
        assertEquals(1, groups.distinct().size)
        assertEquals(ys, ys.map { y -> textYBucket(y) + decodeTextYBits(0xFFFFFF.encodeTextYBits(y - textYBucket(y))) })

        val nextBucket = ShaderGroup(shader, "hud_font", textYBucket(256))
        assertNotEquals(groups.first(), nextBucket)
    }
}
