import kr.toxicity.command.BetterCommandSource
import kr.toxicity.command.SenderType
import kr.toxicity.hud.element.HudElement
import kr.toxicity.hud.layout.HudLayout
import kr.toxicity.hud.layout.LayoutGroup
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.placeholder.ColorOverride
import kr.toxicity.hud.placeholder.ConditionBuilder
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.yaml.YamlObjectImpl
import net.kyori.adventure.audience.Audience
import org.yaml.snakeyaml.Yaml
import java.util.Locale
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ClipOriginTest {

    private object Element : HudElement {
        override val id = "test"
        override val conditions = ConditionBuilder.alwaysTrue
        override val colorOverrides = ColorOverride.empty
    }

    private object Source : BetterCommandSource {
        override fun audience(): Audience = Audience.empty()
        override fun locale(): Locale = Locale.US
        override fun hasPermission(permission: String) = true
        override fun type(): SenderType = SenderType.CONSOLE
    }

    private fun obj(yaml: String): YamlObjectImpl {
        val loaded = Yaml().load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlObjectImpl("", LinkedHashMap(loaded))
    }

    private fun layout(yaml: String): HudLayout<HudElement> {
        val section = obj(yaml)
        return HudLayout.Impl<HudElement>(Element, LayoutGroup("test", Source, section), PixelLocation.zero, section)
    }

    @Test
    fun testNoClipOrigin() {
        val layout = layout("clip: 96")
        assertNull(layout.clipOriginX)
        assertNull(layout.clipOriginY)
        assertEquals(96.0, layout.clipOuter)
    }

    @Test
    fun testClipOrigin() {
        val layout = layout("clip: 96\nclip-origin: [192, 192]")
        assertEquals(192.0, layout.clipOriginX)
        assertEquals(192.0, layout.clipOriginY)
        assertEquals(96.0, layout.clipOuter)
    }

    @Test
    fun testClipOriginIsPartOfShaderIdentity() {
        val plain = shader(null, 96.0)
        val centered = shader(192.0, 96.0)
        assertNotEquals(plain, centered)
        // the same origin must stay the same key, otherwise every glyph gets its own shader id
        assertEquals(0, plain.compareTo(shader(null, 96.0)))
        assertNotEquals(0, plain.compareTo(centered))
        assertNotEquals(0, centered.compareTo(shader(128.0, 96.0)))
        // TreeMap is keyed by compareTo: two elements differing only in clip-origin must not share a case id
        val cases = TreeMap<HudShader, Int>()
        cases[plain] = 1
        cases[centered] = 2
        cases[shader(128.0, 96.0)] = 3
        assertEquals(3, cases.size)
        cases[shader(192.0, 96.0)] = 4
        assertEquals(3, cases.size)
    }

    private fun shader(origin: Double?, clipOuter: Double) = HudShader(
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
        clipOuter,
        origin ?: 0.0,
        origin ?: 0.0,
        origin != null,
    )
}
