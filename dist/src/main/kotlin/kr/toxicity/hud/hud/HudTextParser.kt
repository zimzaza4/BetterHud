package kr.toxicity.hud.hud

import kr.toxicity.hud.api.component.PixelComponent
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.layout.TextLayout
import kr.toxicity.hud.layout.buildTextData
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.renderer.TextRenderer
import kr.toxicity.hud.resource.GlobalResource
import kr.toxicity.hud.shader.HudShader

class HudTextParser(
    globalIndex: Int,
    parent: HudImpl,
    resource: GlobalResource,
    private val text: TextLayout,
    gui: GuiLocation,
    pixel: PixelLocation
) : HudSubParser {

    private val renderer = run {
        val loc = text.location + pixel
        val render = text.renderScale + pixel
        val shader = HudShader(
            gui,
            render,
            text.layer,
            text.outline,
            loc.opacity,
            text.property
        )
        val index2 = ++parent.textIndex
        TextRenderer(
            text,
            text.buildTextData(
                shader,
                loc.y,
                resource.font,
                "hud_${parent.name}_text_${globalIndex}_${index2 + 1}"
            ),
            loc.x
        )
    }.render(UpdateEvent.EMPTY)

    override fun render(player: HudPlayer): (Long) -> PixelComponent = renderer(player)
}