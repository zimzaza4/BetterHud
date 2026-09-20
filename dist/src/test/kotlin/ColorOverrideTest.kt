import kr.toxicity.hud.placeholder.ColorOverride
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.util.toTextColor
import kr.toxicity.hud.yaml.YamlObjectImpl
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ColorOverrideTest {

    private fun section(yaml: String): YamlObjectImpl {
        val loaded = Yaml().load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlObjectImpl("", LinkedHashMap(loaded))
    }

    @Test
    fun testLiteralColor() {
        assertEquals(TextColor.fromHexString("#FF0000"), "#FF0000".toTextColor())
        assertEquals(NamedTextColor.RED, "red".toTextColor())
    }

    @Test
    fun testPlaceholderColorBecomesWhite() {
        assertEquals(NamedTextColor.WHITE, "[ce_team_color]".toTextColor())
        assertEquals(NamedTextColor.WHITE, "<ce_team_color>".toTextColor())
    }

    @Test
    fun testDefaultColorThrows() {
        val object0 = section(
            """
            color-overrides:
              1:
                first: "true"
                second: "true"
                operation: "=="
                color: "#FF0000"
              default-color: "#00FF00"
            """.trimIndent()
        )
        val overrides = object0["color-overrides"]!!.asObject()
        assertFailsWith<RuntimeException> {
            ColorOverride.builder(overrides, PlaceholderSource.Impl(overrides))
        }
    }
}
