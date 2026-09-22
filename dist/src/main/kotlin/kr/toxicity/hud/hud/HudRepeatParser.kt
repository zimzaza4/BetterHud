package kr.toxicity.hud.hud

import kr.toxicity.hud.api.component.PixelComponent
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.layout.RepeatLayout
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.resource.GlobalResource
import kr.toxicity.hud.util.Runner
import kr.toxicity.hud.util.handle
import kotlin.math.roundToInt

class HudRepeatParser(
    hud: HudImpl,
    resource: GlobalResource,
    private val repeat: RepeatLayout,
    gui: GuiLocation,
    pixel: PixelLocation
) {
    private val instances = (0..<repeat.max).mapNotNull { index ->
        runCatching {
            HudParser(hud, resource, repeat.instance(index), gui + repeat.gui, pixel + repeat.location(index))
        }.onFailure {
            it.handle(repeat.sender, "Unable to build instance ${index + 1} of ${repeat.id}: ${it.message}")
        }.getOrNull()
    }
    private val conditions = repeat.conditions build UpdateEvent.EMPTY
    /** `source` is optional: without it every compiled instance is rendered (see `entries`). */
    private val source = repeat.source?.build(UpdateEvent.EMPTY)

    fun getComponent(player: HudPlayer): Runner<List<PixelComponent>> {
        val renderer = instances.map {
            it.getComponent(player)
        }
        return Runner {
            if (conditions(player)) {
                val count = source?.let {
                    (it(player) as Number).toDouble().roundToInt().coerceIn(0, instances.size)
                } ?: instances.size
                renderer.take(count).mapIndexed { index, runner ->
                    PixelComponent(runner(), (repeat.origin(index) + PixelLocation(repeat.shift(index, count), 0, 1.0)).x)
                }
            } else emptyList()
        }
    }
}
