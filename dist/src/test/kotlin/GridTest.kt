import kr.toxicity.hud.layout.enums.RepeatAlign
import kr.toxicity.hud.layout.gridOrigin
import kr.toxicity.hud.layout.gridRowCount
import kr.toxicity.hud.layout.repeatShift
import kotlin.test.Test
import kotlin.test.assertEquals

class GridTest {

    @Test
    fun testOrigin() {
        assertEquals(0, gridOrigin(0, 5, -24, 30).x)
        assertEquals(0, gridOrigin(0, 5, -24, 30).y)
        assertEquals(-96, gridOrigin(4, 5, -24, 30).x)
        assertEquals(30, gridOrigin(5, 5, -24, 30).y)
        assertEquals(-24, gridOrigin(11, 5, -24, 30).x)
        assertEquals(60, gridOrigin(11, 5, -24, 30).y)
    }

    @Test
    fun testRowCount() {
        assertEquals(5, gridRowCount(0, 12, 5))
        assertEquals(5, gridRowCount(4, 12, 5))
        assertEquals(2, gridRowCount(10, 12, 5))
        assertEquals(2, gridRowCount(11, 12, 5))
        assertEquals(3, gridRowCount(2, 3, 5))
        assertEquals(0, gridRowCount(5, 3, 5))
    }

    @Test
    fun testPerRowShift() {
        assertEquals(48, repeatShift(gridRowCount(0, 12, 5), -24, RepeatAlign.CENTER))
        assertEquals(12, repeatShift(gridRowCount(10, 12, 5), -24, RepeatAlign.CENTER))
        assertEquals(12, repeatShift(gridRowCount(11, 12, 5), -24, RepeatAlign.CENTER))
        assertEquals(0, repeatShift(gridRowCount(0, 12, 5), -24, RepeatAlign.START))
    }
}
