package kr.toxicity.hud.api.hud;

import kr.toxicity.hud.api.player.HudPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * How one hud is viewed by one player: whose data it shows, and which of its entries are not shown.
 * <p>
 * A hud is normally rendered with the data of the player it is shown to. An observer - someone
 * spectating another player, watching somebody's screen, ... - has to see the watched player's
 * values instead: his health, his kills, the crosshair of the item he is holding. Only the plugin
 * which manages the hud knows who watches whom, so it answers both questions here, and the hud
 * definition itself stays free of any audience-specific rule (one definition, mounted for the
 * player and for the people watching him).
 * <p>
 * Both answers are asked again on every update, so they may change while the hud stays mounted:
 * an observer switching to another target, or respawning as himself, takes effect on the next frame.
 *
 * @see kr.toxicity.hud.api.BetterHud#setHudViewOption(HudViewOption)
 */
public interface HudViewOption {
    /**
     * Returns the player whose data this hud is rendered with.
     * <p>
     * Every placeholder inside the hud - including the ones registered by other plugins - is
     * resolved against him: his uuid, name, location, world and head. Everything which is not data
     * (the tick the animations run on, the components the hud is written into, popups, variables)
     * stays the viewer's.
     * @param hud the hud being rendered
     * @param viewer the player the hud is shown to
     * @return the data owner; return the viewer himself for the usual case
     */
    @NotNull HudPlayer dataOf(@NotNull Hud hud, @NotNull HudPlayer viewer);

    /**
     * Returns the entries which must not be rendered for this player.
     * <p>
     * An entry is one key under {@code layouts} of the hud, so a hud can be shared between a player
     * and his observers while a few of its elements stay his own.
     * @param hud the hud being rendered
     * @param viewer the player the hud is shown to
     * @return entry keys to skip
     */
    default @NotNull Set<String> hiddenEntriesOf(@NotNull Hud hud, @NotNull HudPlayer viewer) {
        return Set.of();
    }

    /**
     * Whether this hud may ever show somebody else's data.
     * <p>
     * Only huds which answer true are wrapped in a data proxy, and that is not just a shortcut:
     * BetterHud keeps its interpolation state per {@link HudPlayer} <b>identity</b> (weak maps keyed
     * by the player). A wrapped player is a different object, so wrapping a hud which never switches
     * data throws its smoothing away every frame - the element is still drawn, but its tweening is
     * gone. Answer false (the default) for every hud you do not actually scope to another player.
     * @param hud the hud being rendered
     * @return whether {@link #dataOf} may return somebody other than the viewer for this hud
     */
    default boolean usesSubject(@NotNull Hud hud) {
        return false;
    }

    /**
     * The option which renders a hud exactly like it is rendered without any option:
     * the viewer's own data, nothing hidden.
     */
    @NotNull HudViewOption SELF = (hud, viewer) -> viewer;
}
