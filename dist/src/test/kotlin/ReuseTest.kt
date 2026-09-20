import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.yaml.YamlObjectImpl
import kr.toxicity.hud.yaml.forEachExpanded
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals

class ReuseTest {

    private fun load(yaml: String): YamlObjectImpl {
        val loaded = Yaml().load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlObjectImpl("", LinkedHashMap(loaded))
    }

    private fun YamlObject.path(vararg keys: String): YamlObject {
        var current = this
        keys.forEach {
            current = current.get(it)!!.asObject()
        }
        return current
    }

    private fun YamlObject.strings(key: String) = get(key)!!.asArray().map { it.asString() }

    @Test
    fun testAnchorReuse() {
        val root = load(
            """
            bhui_label:
              type: bitmap
              chars:
                row: &bhui_label_row
                  file: "bhui/label_zh.png"
                  ascent: 0
                  codepoints:
                    - "玩家列表数量：0123456789,"
            bhui_label_en:
              type: bitmap
              chars:
                row:
                  <<: *bhui_label_row
                  file: "bhui/label_en.png"
            """.trimIndent()
        )
        val zh = root.path("bhui_label", "chars", "row")
        val en = root.path("bhui_label_en", "chars", "row")
        assertEquals(listOf("玩家列表数量：0123456789,"), zh.strings("codepoints"))
        assertEquals(listOf("玩家列表数量：0123456789,"), en.strings("codepoints"))
        assertEquals("bhui/label_zh.png", zh.get("file")!!.asString())
        assertEquals("bhui/label_en.png", en.get("file")!!.asString())
        assertEquals(0, en.get("ascent")!!.asInt())
    }

    @Test
    fun testForEachRecordReuse() {
        val root = load(
            """
            "bhui_label_{lang}":
              for-each:
                - { lang: zh_cn, text: "玩家列表数量：0123456789,", file: label_zh.png }
                - { lang: en_us, text: "Player List Count: 0123456789,", file: label_en.png }
              type: bitmap
              chars:
                row:
                  file: "bhui/{file}"
                  ascent: 0
                  codepoints:
                    - "{text}"
            """.trimIndent()
        )
        val expanded = LinkedHashMap<String, YamlObjectImpl>()
        root.forEachExpanded { name, yamlObject -> expanded[name] = yamlObject as YamlObjectImpl }
        assertEquals(setOf("bhui_label_zh_cn", "bhui_label_en_us"), expanded.keys)
        val zh = expanded.getValue("bhui_label_zh_cn").path("chars", "row")
        val en = expanded.getValue("bhui_label_en_us").path("chars", "row")
        assertEquals("bhui/label_zh.png", zh.get("file")!!.asString())
        assertEquals("bhui/label_en.png", en.get("file")!!.asString())
        assertEquals(listOf("玩家列表数量：0123456789,"), zh.strings("codepoints"))
        assertEquals(listOf("Player List Count: 0123456789,"), en.strings("codepoints"))
    }
}
