import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.util.toConditions
import kr.toxicity.hud.yaml.YamlObjectImpl
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class ConditionYamlTest {

    private fun section(yaml: String): YamlObjectImpl {
        val loaded = Yaml().load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlObjectImpl("", LinkedHashMap(loaded))
    }

    private fun conditions(yaml: String) = section(yaml).let {
        it.toConditions(PlaceholderSource.Impl(it))
    }

    @Test
    fun testLiteral() {
        conditions("if: \"'a' == 'a'\"")
        conditions("if:\n  - \"'a' == 'a'\"\n  - \"'b' == 'b'\"")
        conditions("if:\n  all:\n    - \"'a' == 'a'\"\n    - \"'b' == 'b'\"")
        conditions("if:\n  any:\n    - \"'a' == 'a'\"")
    }

    @Test
    fun testDispatch() {
        listOf(
            "if: \"a == 1\"",
            "if:\n  - \"a == 1\"",
            "if:\n  all:\n    - \"a == 1\"",
            "if:\n  all:\n    - any:\n        - \"a == 1\""
        ).forEach {
            val failure = assertFailsWith<RuntimeException>(it) { conditions(it) }
            assertFalse(failure.message.orEmpty().contains("unsupported condition"), it)
        }
    }
}
