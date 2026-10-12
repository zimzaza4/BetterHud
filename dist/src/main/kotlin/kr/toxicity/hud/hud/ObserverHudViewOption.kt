package kr.toxicity.hud.hud

import kr.toxicity.hud.api.hud.Hud
import kr.toxicity.hud.api.hud.HudViewOption
import kr.toxicity.hud.api.manager.ConfigManager
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.manager.PlaceholderManagerImpl
import kr.toxicity.hud.manager.PlayerManagerImpl
import kr.toxicity.hud.placeholder.Placeholder
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.util.debug
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The `observer` block of one hud, as it is written in the hud's own yml:
 *
 * ```yaml
 * observer:
 *   target: "%ce_spectating_player%"   # a placeholder, a player name or a uuid
 *   hidden-entries: [name]             # layouts the observer does not see, while he watches somebody else
 * ```
 */
internal class ObserverOption(
    private val target: ((HudPlayer) -> String)?,
    private val hiddenEntries: Set<String>
) : HudViewOption {

    override fun usesSubject(hud: Hud): Boolean = target != null

    private class Resolved(val viewer: HudPlayer, val tick: Long, val player: HudPlayer)

    private val resolved = ConcurrentHashMap<UUID, Resolved>()

    /**
     * The answer is resolved once per tick of the viewer: every access would otherwise evaluate the
     * target placeholder again (and do a name lookup with it), and a hud asks for it repeatedly per
     * frame. The entry is dropped when the player object it was resolved for is rebuilt, so a rejoin
     * or a reload never keeps answering from the old one.
     */
    override fun dataOf(hud: Hud, viewer: HudPlayer): HudPlayer {
        val getter = this.target ?: return viewer
        val tick = viewer.tick
        resolved[viewer.uuid()]?.let {
            if (it.viewer === viewer && it.tick == tick) return it.player
        }
        if (resolved.size >= PRUNE_THRESHOLD) {
            resolved.keys.removeIf { PlayerManagerImpl.getHudPlayer(it) == null }
        }
        val player = resolve(getter, viewer)
        resolved[viewer.uuid()] = Resolved(viewer, tick, player)
        return player
    }

    /** The declared entries, dropped only while this viewer actually watches somebody else. */
    override fun hiddenEntriesOf(hud: Hud, viewer: HudPlayer): Set<String> =
        if (hiddenEntries.isEmpty() || dataOf(hud, viewer) === viewer) emptySet() else hiddenEntries

    /** An empty, `null` or unknown answer means nobody is watched: the viewer sees his own data. */
    private fun resolve(getter: (HudPlayer) -> String, viewer: HudPlayer): HudPlayer {
        val name = runCatching {
            getter(viewer)
        }.getOrNull()?.trim()
        if (name.isNullOrEmpty() || name.equals("null", true)) return viewer
        val uuid = runCatching {
            UUID.fromString(name)
        }.getOrNull()
        return if (uuid != null) PlayerManagerImpl.getHudPlayer(uuid) ?: viewer
        else PlayerManagerImpl.getHudPlayer(name) ?: viewer
    }

    companion object {
        private const val PRUNE_THRESHOLD = 64

        fun parse(source: PlaceholderSource, section: YamlObject): ObserverOption {
            val target = section.getAsString("target", "").trim()
            return ObserverOption(
                target.takeIf {
                    it.isNotEmpty() && !it.equals("null", true)
                }?.let {
                    toTarget(it, source)
                },
                section["hidden-entries"]?.asArray()?.map {
                    it.asString()
                }?.toSet() ?: emptySet()
            )
        }

        private fun toTarget(target: String, source: PlaceholderSource): (HudPlayer) -> String {
            val placeholder = names(target).firstNotNullOfOrNull {
                placeholder(it, source)
            }
            if (placeholder != null) return {
                placeholder.stringValue(it)
            }
            // Not a registered placeholder, so it names a player directly.
            debug(
                ConfigManager.DebugLevel.MANAGER,
                "The observer target is not a registered placeholder, so it names a player: $target"
            )
            return {
                text(target)
            }
        }

        /** The spellings a target may be written in, most specific first (`%name%` is read as `papi:name`). */
        private fun names(target: String): List<String> = when {
            target.startsWith('%') && target.endsWith('%') -> listOf("papi:${text(target)}", text(target))
            target.startsWith('[') && target.endsWith(']') -> listOf(text(target))
            else -> listOf(target)
        }

        /** A target without the `%` or `[]` it may be quoted in: the part which names a player. */
        private fun text(target: String): String {
            val quoted = target.length > 2 && (target.startsWith('%') && target.endsWith('%') ||
                    target.startsWith('[') && target.endsWith(']'))
            return if (quoted) target.substring(1, target.length - 1) else target
        }

        private fun placeholder(name: String, source: PlaceholderSource): Placeholder<*>? = runCatching {
            PlaceholderManagerImpl.find(name, source)
                .assertString("This observer target is not a string: $name")
                .build(UpdateEvent.EMPTY)
        }.getOrNull()
    }
}

/** The [HudViewOption] read from the huds' own yml; it stays silent for every hud which declares none. */
internal object ObserverHudViewOption : HudViewOption {

    override fun usesSubject(hud: Hud): Boolean = option(hud)?.usesSubject(hud) == true

    override fun dataOf(hud: Hud, viewer: HudPlayer): HudPlayer = option(hud)?.dataOf(hud, viewer) ?: viewer

    override fun hiddenEntriesOf(hud: Hud, viewer: HudPlayer): Set<String> =
        option(hud)?.hiddenEntriesOf(hud, viewer) ?: emptySet()

    /** Only the huds BetterHud itself built can carry a declaration. */
    private fun option(hud: Hud): ObserverOption? = (hud as? HudImpl)?.observerOption
}
