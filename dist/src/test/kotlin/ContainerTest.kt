import kr.toxicity.hud.api.component.PixelComponent
import kr.toxicity.hud.api.component.WidthComponent
import kr.toxicity.hud.component.LayoutComponentContainer
import kr.toxicity.hud.layout.enums.LayoutAlign
import kr.toxicity.hud.layout.enums.LayoutOffset
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerTest {

    private fun build(offset: LayoutOffset, align: LayoutAlign, max: Int, pixel: Int): WidthComponent =
        LayoutComponentContainer(offset, align, max)
            .append(
                listOf(
                    PixelComponent(WidthComponent(Component.text().content("x"), 20), pixel)
                )
            )
            .build()

    @Test
    fun testCenteredBoxCarriesLeadingShift() {
        assertEquals(-160, build(LayoutOffset.CENTER, LayoutAlign.LEFT, 320, -34).width)
    }

    @Test
    fun testLeftBoxCarriesNoLeadingShift() {
        assertEquals(0, build(LayoutOffset.LEFT, LayoutAlign.LEFT, 320, -34).width)
    }

    @Test
    fun testRightBoxCarriesFullShift() {
        assertEquals(-320, build(LayoutOffset.RIGHT, LayoutAlign.LEFT, 320, -34).width)
    }

    @Test
    fun testElementPixelDoesNotChangeDeclaredWidth() {
        assertEquals(build(LayoutOffset.CENTER, LayoutAlign.LEFT, 320, 77).width, build(LayoutOffset.CENTER, LayoutAlign.LEFT, 320, -19).width)
    }
}
