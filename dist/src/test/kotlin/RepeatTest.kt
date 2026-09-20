import kr.toxicity.hud.layout.enums.RepeatAlign
import kr.toxicity.hud.layout.repeatPitch
import kr.toxicity.hud.layout.repeatShift
import kr.toxicity.hud.layout.repeatVariables
import kotlin.test.Test
import kotlin.test.assertEquals

class RepeatTest {

    @Test
    fun testPitch() {
        assertEquals(24, repeatPitch(null, 0, 24))
        assertEquals(-24, repeatPitch(null, 0, -24))
        assertEquals(22, repeatPitch(20, 2, 0))
    }

    @Test
    fun testShiftLeftwards() {
        assertEquals(0, repeatShift(5, -24, RepeatAlign.START))
        assertEquals(48, repeatShift(5, -24, RepeatAlign.CENTER))
        assertEquals(96, repeatShift(5, -24, RepeatAlign.END))
    }

    @Test
    fun testShiftRightwards() {
        assertEquals(0, repeatShift(5, 24, RepeatAlign.START))
        assertEquals(-48, repeatShift(5, 24, RepeatAlign.CENTER))
        assertEquals(-96, repeatShift(5, 24, RepeatAlign.END))
    }

    @Test
    fun testSingleInstance() {
        assertEquals(0, repeatShift(1, -24, RepeatAlign.END))
        assertEquals(0, repeatShift(0, -24, RepeatAlign.CENTER))
    }

    @Test
    fun testVariables() {
        assertEquals(mapOf("y" to 14, "i" to 3), repeatVariables(2, "i", 14, emptyMap()))
        assertEquals(mapOf("y" to 0, "team" to "ct", "slot" to 1), repeatVariables(0, "slot", 0, mapOf("team" to "ct")))
        assertEquals(9, repeatVariables(0, "i", 5, mapOf("y" to 9))["y"])
        assertEquals(0, repeatVariables(4, "i", 0, emptyMap())["y"])
    }
}
