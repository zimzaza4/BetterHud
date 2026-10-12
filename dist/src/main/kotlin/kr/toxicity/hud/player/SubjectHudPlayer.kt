package kr.toxicity.hud.player

import kr.toxicity.hud.api.hud.Hud
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.manager.HudViewManager

/**
 * The player one hud is rendered *for*, whose data is read from whoever [HudViewManager] names.
 *
 * The answer is resolved once per tick of the viewer and then reused: a placeholder asks for the same
 * uuid a dozen times per frame, and every access re-resolving it would run the whole view-option chain
 * (two plugins' answers plus two `runCatching`) again and again. `tick` advances on every update of
 * this player, so the cache follows an observer switching targets without lagging.
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
    override fun gameTime() = data.gameTime()
}
