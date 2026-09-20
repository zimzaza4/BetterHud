import kr.toxicity.hud.placeholder.ConditionExpression
import kr.toxicity.hud.placeholder.ConditionExpression.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConditionExpressionTest {

    private fun v(text: String) = Node.Value(text)

    @Test
    fun testComparison() {
        assertEquals(
            Node.Compare(v("item_tag:crosshair"), "==", v("'shotgun'")),
            ConditionExpression.node("item_tag:crosshair == 'shotgun'")
        )
        assertEquals(
            Node.Compare(v("a"), "!=", v("b")),
            ConditionExpression.node("a != b")
        )
    }

    @Test
    fun testPrecedence() {
        assertEquals(
            Node.Either(listOf(Node.Both(listOf(v("a"), v("b"))), v("c"))),
            ConditionExpression.node("a && b || c")
        )
        assertEquals(
            Node.Both(listOf(Node.Either(listOf(v("a"), v("b"))), v("c"))),
            ConditionExpression.node("(a || b) && c")
        )
    }

    @Test
    fun testNegation() {
        assertEquals(Node.Negate(v("a")), ConditionExpression.node("!a"))
        assertEquals(
            Node.Both(listOf(Node.Negate(v("a")), v("b"))),
            ConditionExpression.node("!a && b")
        )
    }

    @Test
    fun testAmong() {
        assertEquals(
            Node.Among(v("a"), listOf(v("'x'"), v("'y'"))),
            ConditionExpression.node("a in ('x', 'y')")
        )
    }

    @Test
    fun testValues() {
        assertEquals(
            Node.Compare(v("(number)ce_damage_angle"), ">=", v("112.5")),
            ConditionExpression.node("(number)ce_damage_angle >= 112.5")
        )
        assertEquals(
            Node.Compare(v("[item_tag:crosshair]"), "==", v("'aim'")),
            ConditionExpression.node("[item_tag:crosshair] == 'aim'")
        )
        assertEquals(v("ce_damaged_all"), ConditionExpression.node("ce_damaged_all"))
        assertEquals(v("ce_team_ct_exist:{slot}"), ConditionExpression.node("ce_team_ct_exist:{slot}"))
    }

    @Test
    fun testFailure() {
        assertFailsWith<RuntimeException> { ConditionExpression.node("a && ") }
        assertFailsWith<RuntimeException> { ConditionExpression.node("a == 'x") }
        assertFailsWith<RuntimeException> { ConditionExpression.node("(a") }
        assertFailsWith<RuntimeException> { ConditionExpression.node("a b") }
        assertFailsWith<RuntimeException> { ConditionExpression.node("a in ()") }
    }

    @Test
    fun testCache() {
        assertSame(
            ConditionExpression.node("item_tag:crosshair == 'melee' && !is_aiming"),
            ConditionExpression.node("item_tag:crosshair == 'melee' && !is_aiming")
        )
    }

    @Test
    fun testParseCost() {
        val start = System.nanoTime()
        repeat(20_000) {
            ConditionExpression.node("item_tag:crosshair$it in ('null','shotgun','projectile') && !is_aiming")
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsed < 3_000, "20,000 parses took $elapsed ms")
    }
}
