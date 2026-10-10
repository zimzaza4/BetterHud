package kr.toxicity.hud.player

import kr.toxicity.hud.api.hud.Hud
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.manager.HudViewManager

/**
 * The player one hud is rendered *for*, whose data is read from whoever [HudViewManager] names.
 *
 * Data accessors are resolved on every call - with a one-per-tick cache, since a placeholder asks for
 * the same uuid a dozen times per frame - so an observer switching to another target follows on the
 * next update without the hud being re-created.
 *
 * Everything which is not data stays the viewer's: the tick the animations run on, the components
 * the hud is written into, his variables, his popups, his hud set.
 */
internal class SubjectHudPlayer(
    private val hud: Hud,
    private val viewer: HudPlayer
) : HudPlayer by viewer {

    private var resolvedTick: Long = Long.MIN_VALUE
    private var resolved: HudPlayer = viewer

    private val data: HudPlayer
        get() {
            val tick = viewer.tick
            if (tick != resolvedTick) {
                resolvedTick = tick
                resolved = HudViewManager.dataOf(hud, viewer)
            }
            return resolved
        }

    override fun uuid() = data.uuid()
    override fun name() = data.name()
    override fun location() = data.location()
    override fun world() = data.world()
    override fun handle() = data.handle()
    override fun getHead() = data.getHead()
}
