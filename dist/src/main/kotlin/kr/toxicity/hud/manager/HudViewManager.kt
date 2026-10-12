package kr.toxicity.hud.manager

import kr.toxicity.hud.api.hud.Hud
import kr.toxicity.hud.api.hud.HudViewOption
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.hud.ObserverHudViewOption

/**
 * Registry of the hud view option (see [HudViewOption]): the plugin which manages a hud answers
 * whose data it shows and which entries a player does not see.
 *
 * What a hud declares in its own yml (its `observer` block) answers where that option has nothing to
 * say, and the hidden entries are the union of both.
 */
object HudViewManager {

    @Volatile
    private var option: HudViewOption = HudViewOption.SELF

    val current: HudViewOption
        get() = option

    /** Whether a plugin registered something (false = render exactly like before this existed). */
    val isCustom: Boolean
        get() = option !== HudViewOption.SELF

    /** What the huds themselves declare, asked where [option] has nothing to say. */
    private val builtin: HudViewOption
        get() = ObserverHudViewOption

    fun set(option: HudViewOption?) {
        this.option = option ?: HudViewOption.SELF
    }

    fun clear() = set(null)

    /**
     * Whether this hud may show somebody else's data. Only these are wrapped in a data proxy: the
     * renderers key their interpolation state by player identity, so a needless wrap costs its tweening.
     */
    fun usesSubject(hud: Hud): Boolean =
        (isCustom && runCatching { option.usesSubject(hud) }.getOrDefault(false)) ||
            runCatching { builtin.usesSubject(hud) }.getOrDefault(false)

    /** Whose data this hud shows - the viewer looking at it, unless one of the two options names somebody else. */
    fun dataOf(hud: Hud, viewer: HudPlayer): HudPlayer {
        if (isCustom) {
            val registered = runCatching { option.dataOf(hud, viewer) }.getOrNull()
            // The viewer himself is the plugin saying it has nothing to say about this hud.
            if (registered != null && registered !== viewer) return registered
        }
        return runCatching { builtin.dataOf(hud, viewer) }.getOrDefault(viewer)
    }

    /** Entries which are not rendered for this viewer: the union of both sources, neither wins. */
    fun hiddenEntriesOf(hud: Hud, viewer: HudPlayer): Set<String> {
        val registered = if (isCustom) runCatching {
            option.hiddenEntriesOf(hud, viewer)
        }.getOrDefault(emptySet()) else emptySet()
        val declared = runCatching { builtin.hiddenEntriesOf(hud, viewer) }.getOrDefault(emptySet())
        return when {
            declared.isEmpty() -> registered
            registered.isEmpty() -> declared
            else -> registered + declared
        }
    }
}
