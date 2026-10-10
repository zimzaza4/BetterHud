package kr.toxicity.hud.manager

import kr.toxicity.hud.api.hud.Hud
import kr.toxicity.hud.api.hud.HudViewOption
import kr.toxicity.hud.api.player.HudPlayer

/**
 * Registry of the hud view option (see [HudViewOption]).
 *
 * The plugin which manages a hud is the one which knows who watches whom, so it answers here whose
 * data a hud shows and which of its entries a player does not see. The option is asked again on
 * every update, and it survives a reload (a plugin only has to set it once); [clear] drops it when
 * BetterHud is disabled.
 *
 * A broken option must never take a hud down: every answer falls back to [HudViewOption.SELF], and
 * when nothing is registered there is no wrapper and no lookup at all.
 */
object HudViewManager {

    @Volatile
    private var option: HudViewOption = HudViewOption.SELF

    val current: HudViewOption
        get() = option

    /** Whether a plugin registered something (false = render exactly like before this existed). */
    val isCustom: Boolean
        get() = option !== HudViewOption.SELF

    fun set(option: HudViewOption?) {
        this.option = option ?: HudViewOption.SELF
    }

    fun clear() = set(null)

    /**
     * Whether this hud may ever show somebody else's data.
     *
     * Only huds answering true are wrapped in a data proxy, and that is not just a shortcut: the
     * renderers keep their interpolation state in a weak map keyed by [HudPlayer] identity, so a
     * hud wrapped for nothing loses its tweening every frame while still being drawn.
     */
    fun usesSubject(hud: Hud): Boolean =
        isCustom && runCatching { option.usesSubject(hud) }.getOrDefault(false)

    /** Whose data this hud shows - the viewer looking at it, unless the option names somebody else. */
    fun dataOf(hud: Hud, viewer: HudPlayer): HudPlayer =
        if (!isCustom) viewer
        else runCatching { option.dataOf(hud, viewer) }.getOrDefault(viewer)

    /** Which entries of this hud are not rendered for this viewer. */
    fun hiddenEntriesOf(hud: Hud, viewer: HudPlayer): Set<String> =
        if (!isCustom) emptySet()
        else runCatching { option.hiddenEntriesOf(hud, viewer) }.getOrDefault(emptySet())
}
