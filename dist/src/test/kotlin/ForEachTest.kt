import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.yaml.YamlObjectImpl
import kr.toxicity.hud.yaml.forEachExpanded
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForEachTest {

    private fun expand(yaml: String): List<Pair<String, YamlObject>> {
        val loaded = Yaml().load<Map<String, Any>>(yaml) ?: emptyMap()
        val result = ArrayList<Pair<String, YamlObject>>()
        YamlObjectImpl("", LinkedHashMap(loaded)).forEachExpanded { name, yamlObject ->
            result += name to yamlObject
        }
        return result
    }

    private fun List<Pair<String, YamlObject>>.names() = map { it.first }

    private fun List<Pair<String, YamlObject>>.entry(name: String) = first { it.first == name }.second

    @Test
    fun testRange() {
        val expanded = expand(
            """
            "avatar_{i}":
              for-each: 1..3
              name: "ce_avatar_{i}"
              x: "{i}"
              conditions:
                1:
                  first: "ce_exist:{i}"
                  second: "true"
                  operation: "=="
            """.trimIndent()
        )
        assertEquals(listOf("avatar_1", "avatar_2", "avatar_3"), expanded.names())
        assertEquals("ce_avatar_2", expanded.entry("avatar_2")["name"]!!.asString())
        assertEquals(2, expanded.entry("avatar_2")["x"]!!.asInt())
        assertEquals(
            "ce_exist:3",
            expanded.entry("avatar_3")["conditions"]!!.asObject()["1"]!!.asObject()["first"]!!.asString()
        )
        assertNull(expanded.entry("avatar_1")["for-each"])
    }

    @Test
    fun testList() {
        val expanded = expand(
            """
            "row_{i}":
              for-each: [ct, t]
              name: "ce_{i}_status"
            """.trimIndent()
        )
        assertEquals(listOf("row_ct", "row_t"), expanded.names())
        assertEquals("ce_ct_status", expanded.entry("row_ct")["name"]!!.asString())
    }

    @Test
    fun testCartesian() {
        val expanded = expand(
            """
            "ce_{team}_slot_{slot}":
              for-each:
                team: [ct, t]
                slot: 1..2
              pattern: "[ce_team_{team}_health:{slot}]"
            """.trimIndent()
        )
        assertEquals(
            listOf("ce_ct_slot_1", "ce_ct_slot_2", "ce_t_slot_1", "ce_t_slot_2"),
            expanded.names()
        )
        assertEquals("[ce_team_t_health:1]", expanded.entry("ce_t_slot_1")["pattern"]!!.asString())
    }

    @Test
    fun testRecord() {
        val expanded = expand(
            """
            "ct_avatar_{slot}":
              for-each:
                - { slot: 1, x: 77 }
                - { slot: 2, x: 53 }
              name: "ce_ct_avatar_{slot}"
              x: "{x}"
            """.trimIndent()
        )
        assertEquals(listOf("ct_avatar_1", "ct_avatar_2"), expanded.names())
        assertEquals(77, expanded.entry("ct_avatar_1")["x"]!!.asInt())
        assertEquals(53, expanded.entry("ct_avatar_2")["x"]!!.asInt())
    }

    @Test
    fun testNested() {
        val expanded = expand(
            """
            "sheet_{i}":
              for-each: 1..2
              type: bitmap
              chars:
                1:
                  file: "emoji/sheet_{i}.png"
                  codepoints:
                    - "char_{i}"
            """.trimIndent()
        )
        val chars = expanded.entry("sheet_1")["chars"]!!.asObject()["1"]!!.asObject()
        assertEquals("emoji/sheet_1.png", chars["file"]!!.asString())
        assertEquals(listOf("char_1"), chars["codepoints"]!!.asArray().map { it.asString() })
    }

    @Test
    fun testUntouched() {
        val expanded = expand(
            """
            fixed:
              name: ce_fixed
              x: 1
            """.trimIndent()
        )
        assertEquals(listOf("fixed"), expanded.names())
        assertEquals(1, expanded.entry("fixed")["x"]!!.asInt())
    }

    private fun elements(yamlObject: YamlObject): List<Pair<String, YamlObject>> = buildList {
        listOf("images", "texts", "heads").forEach { section ->
            yamlObject[section]?.asObject()?.forEachExpanded { name, element ->
                add(name to element)
            }
        }
    }

    private fun lines(name: String, yamlObject: YamlObject) = Yaml()
        .dumpAsMap(mapOf(name to yamlObject.get()))
        .trim()
        .lines()
        .size

    @Test
    fun testExampleConfig() {
        val folder = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "examples/for-each") }
            .firstOrNull { it.isDirectory } ?: error("examples/for-each not found")
        val files = folder.walkTopDown().filter { it.extension == "yml" }.sortedBy { it.path }.toList()
        assertTrue(files.isNotEmpty(), "no example yml found in ${folder.path}")
        var written = 0
        var compiled = 0
        var entries = 0
        files.forEach { file ->
            val top = expand(file.readText())
            val children = top.flatMap { elements(it.second) }
            written += file.readLines().size
            compiled += top.sumOf { lines(it.first, it.second) } + children.sumOf { lines(it.first, it.second) }
            entries += top.size + children.size
            println(
                "${file.name}: ${file.readLines().size} lines written -> " +
                    "${top.size} objects, ${children.size} elements, $compiled lines compiled so far"
            )
        }
        println("total: $written lines written -> $entries entries / $compiled lines compiled")
        assertEquals(43, entries)
    }
}
