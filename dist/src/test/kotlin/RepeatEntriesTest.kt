import kr.toxicity.command.BetterCommandSource
import kr.toxicity.command.SenderType
import kr.toxicity.hud.equation.TEquation
import kr.toxicity.hud.layout.RepeatLayout
import kr.toxicity.hud.layout.repeatVariables
import kr.toxicity.hud.yaml.YamlObjectImpl
import net.kyori.adventure.audience.Audience
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `repeats.entries`: the per-instance table of a repeat. */
class RepeatEntriesTest {

    private val sender = object : BetterCommandSource {
        override fun audience(): Audience = Audience.empty()
        override fun locale(): Locale = Locale.US
        override fun hasPermission(permission: String): Boolean = true
        override fun type(): SenderType = SenderType.CONSOLE
    }

    /** `source: "5"` is a literal number, so no placeholder has to be registered here. */
    private fun repeat(vararg pairs: Pair<String, Any>): RepeatLayout =
        RepeatLayout("test", sender, YamlObjectImpl("test", LinkedHashMap(mapOf(*pairs))))

    private fun inline(): Pair<String, Any> =
        "images" to mapOf("cell" to mapOf("name" to "any_name", "x" to "{x}", "y" to "{y}"))

    @Test
    fun testEntryWinsOverRowOffsetAndVariables() {
        assertEquals(150, repeatVariables(0, "i", 0, mapOf("y" to 9), mapOf("y" to 150))["y"])
        assertEquals(99, repeatVariables(0, "i", 0, mapOf("k" to 1), mapOf("k" to 99))["k"])
        assertEquals(
            mapOf("y" to 7, "team" to "ct", "slot" to 1),
            repeatVariables(0, "slot", 0, emptyMap(), mapOf("y" to 7, "team" to "ct"))
        )
        // the instance number is bound last, so `as` still wins over an entry
        assertEquals(3, repeatVariables(2, "i", 5, mapOf("i" to 42), mapOf("i" to 42))["i"])
        assertEquals(1, repeatVariables(0, "slot", 0, mapOf("slot" to 9))["slot"])
    }

    @Test
    fun testSignedOffsetExpression() {
        // `position: ["dx@(t + {sx})"]` with a negative sx reaches the parser as `t + -94`
        assertEquals(-84.0, TEquation("t + -94") evaluate 10.0)
        assertEquals(104.0, TEquation("t + 94") evaluate 10.0)
        assertEquals(10.0, TEquation("t + -0") evaluate 10.0)
    }

    @Test
    fun testEntriesDecideTheInstanceCount() {
        // with entries there is no max (the table is the count), no source (all instances) and no positioning key
        val layout = repeat(
            "entries" to listOf(
                mapOf("name" to "a", "x" to 1),
                mapOf("name" to "b", "x" to 2),
                mapOf("name" to "c", "x" to 3)
            ),
            inline()
        )
        assertEquals(3, layout.max)
        assertNull(layout.source)
    }

    @Test
    fun testSourceAndMaxStillWork() {
        val layout = repeat(
            "source" to "5",
            "max" to 3,
            "grid" to mapOf("per-row" to 1, "x-space" to 0, "y-space" to 1),
            inline()
        )
        assertEquals(3, layout.max)
        assertTrue(layout.source != null)
    }

    @Test
    fun testValidation() {
        assertFailsWith<RuntimeException> {
            repeat(
                "source" to "5",
                "max" to 2,
                "offset" to mapOf("dx" to 0, "dy" to 0),
                "entries" to listOf(mapOf("x" to 1)),
                inline()
            )
        }
        assertFailsWith<RuntimeException> { repeat("max" to 2, "offset" to mapOf("dx" to 0, "dy" to 0), inline()) }
        assertFailsWith<RuntimeException> { repeat("source" to "5", "max" to 2, inline()) }
        assertFailsWith<RuntimeException> {
            repeat("source" to "5", "offset" to mapOf("dx" to 0, "dy" to 0), inline())
        }
        assertFailsWith<RuntimeException> {
            repeat(
                "source" to "5",
                "max" to 2,
                "offset" to mapOf("dx" to 0, "dy" to 0),
                "template" to "t",
                inline()
            )
        }
    }
}
