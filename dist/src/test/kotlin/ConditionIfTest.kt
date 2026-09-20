import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.placeholder.ConditionBuilder
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.util.toConditions
import kr.toxicity.hud.yaml.YamlObjectImpl
import org.yaml.snakeyaml.Yaml
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `if:` 的结构语义：`all` 是合取、`any` 是析取、嵌套表示括号。
 * 这里对表达式做**真实求值**（字面量比较不依赖 player，所以可以传 null）。
 */
class ConditionIfTest {

    private fun evaluate(yaml: String): Boolean {
        val loaded = Yaml().load<Map<String, Any>>(yaml) ?: emptyMap()
        val root = YamlObjectImpl("", LinkedHashMap(loaded))
        val condition: ConditionBuilder = root.toConditions(PlaceholderSource.Impl(root))
        return (condition build UpdateEvent.EMPTY).invoke(fakePlayer())
    }

    /** 字面量比较不需要 player，但求值签名要求非空实例，所以给个只返回默认值的代理。 */
    private fun fakePlayer(): HudPlayer = Proxy.newProxyInstance(
        HudPlayer::class.java.classLoader,
        arrayOf(HudPlayer::class.java)
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }
    } as HudPlayer

    @Test
    fun testAll() {
        assertTrue(evaluate("if:\n  all:\n    - \"'a' == 'a'\"\n    - \"'b' == 'b'\""))
        assertFalse(evaluate("if:\n  all:\n    - \"'a' == 'a'\"\n    - \"'a' == 'b'\""))
        assertTrue(evaluate("if:\n  all: \"'a' == 'a'\""))
    }

    @Test
    fun testAny() {
        // canary: 如果 any 又退化成 AND，这两条都会挂
        assertTrue(evaluate("if:\n  any:\n    - \"'a' == 'b'\"\n    - \"'a' == 'a'\""))
        assertTrue(evaluate("if:\n  any:\n    - \"'a' == 'a'\"\n    - \"'a' == 'b'\""))
        assertFalse(evaluate("if:\n  any:\n    - \"'a' == 'b'\"\n    - \"'c' == 'd'\""))
        assertFalse(evaluate("if:\n  any: \"'a' == 'b'\""))
    }

    @Test
    fun testAllAndAnyTogether() {
        assertTrue(evaluate("if:\n  all:\n    - \"'a' == 'a'\"\n  any:\n    - \"'x' == 'y'\"\n    - \"'y' == 'y'\""))
        assertFalse(evaluate("if:\n  all:\n    - \"'a' == 'a'\"\n  any:\n    - \"'x' == 'y'\""))
        assertFalse(evaluate("if:\n  all:\n    - \"'a' == 'b'\"\n  any:\n    - \"'y' == 'y'\""))
    }

    @Test
    fun testNested() {
        // README 的“背对伤害源”写法：all[ any[...], ... ] —— any 必须是析取才有意义
        // 120 满足 ">= 112.5" 而不满足 "<= -112.5" → any 为 true，all 才能为 true
        assertTrue(evaluate("if:\n  all:\n    - any:\n        - \"120 >= 112.5\"\n        - \"120 <= -112.5\"\n    - \"'a' == 'a'\""))
        assertFalse(evaluate("if:\n  all:\n    - any:\n        - \"120 >= 112.5\"\n        - \"120 <= -112.5\"\n    - \"'a' == 'b'\""))
        // 嵌套里的 all 仍然是合取：两支不可能同时成立 → false
        assertFalse(evaluate("if:\n  any:\n    - all:\n        - \"120 >= 112.5\"\n        - \"120 <= -112.5\""))
    }
}
