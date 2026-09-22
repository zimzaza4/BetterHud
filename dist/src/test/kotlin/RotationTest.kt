import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.PayloadKind
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.shader.Rotation
import kr.toxicity.hud.util.toRotation
import kr.toxicity.hud.yaml.YamlObjectImpl
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RotationTest {

    private fun section(yaml: String): YamlObjectImpl {
        val loaded = Yaml().load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlObjectImpl("", LinkedHashMap(loaded))
    }

    private fun rotationOf(yaml: String): Rotation {
        val section = section(yaml)
        return section.toRotation(PlaceholderSource.Impl(section))
    }

    @Test
    fun testNoRotation() {
        assertEquals(Rotation.None, rotationOf("name: test"))
        assertEquals(0, rotationOf("name: test").mode)
    }

    @Test
    fun testStaticRotation() {
        val rotation = rotationOf("rotation: 25")
        assertEquals(Rotation.Static(25.0), rotation)
        assertEquals(1, rotation.mode)
        assertEquals(25.0, rotation.bakedDegree)
        assertEquals(0.0, rotationOf("name: test").bakedDegree)
    }

    @Test
    fun testRotationIsPartOfShaderIdentity() {
        val plain = shader(false, false, 0.0)
        val rotated = shader(false, false, 25.0)
        assertNotEquals(plain, rotated)
        assertTrue(plain.compareTo(rotated) != 0)
        assertTrue(rotated.compareTo(plain) != 0)
        // the same transform must stay the same key, otherwise every glyph gets its own shader id
        assertEquals(0, plain.compareTo(shader(false, false, 0.0)))
        assertNotEquals(0, plain.compareTo(shader(true, false, 0.0)))
        assertNotEquals(0, plain.compareTo(shader(false, true, 0.0)))
    }

    @Test
    fun testPayloadKind() {
        assertEquals(PayloadKind.NONE, PayloadKind.of(false, false))
        assertEquals(PayloadKind.ROTATION, PayloadKind.of(true, false))
        assertEquals(PayloadKind.POSITION, PayloadKind.of(false, true))
        assertEquals(PayloadKind.ROTATION_POSITION, PayloadKind.of(true, true))
    }

    private fun shader(rotationDynamic: Boolean, positionDynamic: Boolean, degree: Double) = HudShader(
        GuiLocation(0.0, 0.0),
        RenderScale(PixelLocation.zero, RenderScale.Scale(1.0, 1.0, false)),
        0,
        0,
        1.0,
        0,
        degree,
        rotationDynamic,
        positionDynamic,
        64.0,
        64.0,
    )
}
