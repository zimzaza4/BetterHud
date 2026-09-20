package kr.toxicity.hud.hud

import kr.toxicity.hud.api.component.WidthComponent
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.component.LayoutComponentContainer
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.layout.LayoutGroup
import kr.toxicity.hud.layout.enums.LayoutAlign
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.resource.GlobalResource
import kr.toxicity.hud.util.EMPTY_WIDTH_COMPONENT
import kr.toxicity.hud.util.Runner
import kr.toxicity.hud.util.toSpaceComponent

class HudParser(
    hud: HudImpl,
    resource: GlobalResource,
    private val layout: LayoutGroup,
    gui: GuiLocation,
    pixel: PixelLocation
) {
    private val imageElement = layout.image.map { image ->
        HudImageParser(hud, image, gui, pixel)
    }
    private val textElement = layout.text.mapIndexed { index, textLayout ->
        HudTextParser(index + 1, hud, resource, textLayout, gui, pixel)
    }
    private val headElement = layout.head.map { image ->
        HudHeadParser(hud, image, gui, pixel)
    }

    private val repeatElement = layout.repeat.map {
        HudRepeatParser(hud, resource, it, gui, pixel)
    }

    private val elements = listOf(
        imageElement,
        textElement,
        headElement
    ).flatten()

    val conditions = layout.conditions build UpdateEvent.EMPTY

    private val max = imageElement.maxOfOrNull {
        it.max
    } ?: 0

    fun getComponent(player: HudPlayer): Runner<WidthComponent> {
        val renderer = elements.map {
            it.render(player)
        }
        val repeats = repeatElement.map {
            it.getComponent(player)
        }
        return Runner {
            if (conditions(player)) {
                val f = player.tick
                val composed = LayoutComponentContainer(layout.offset, layout.align, max)
                    .append(renderer.map {
                        it(f)
                    })
                    .build()
                val repeated = LayoutComponentContainer(layout.offset, LayoutAlign.LEFT, max)
                    .append(repeats.flatMap {
                        it()
                    })
                    .build()
                composed + (-composed.width).toSpaceComponent() + repeated
            } else EMPTY_WIDTH_COMPONENT
        }
    }
}