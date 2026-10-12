package kr.toxicity.hud.hud

import com.google.gson.JsonArray
import kr.toxicity.hud.api.configuration.HudComponentSupplier
import kr.toxicity.hud.api.configuration.HudObjectType
import kr.toxicity.hud.api.hud.Hud
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.configuration.HudConfiguration
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.animation.AnimationType
import kr.toxicity.hud.manager.ConfigManagerImpl
import kr.toxicity.hud.manager.HudViewManager
import kr.toxicity.hud.manager.LayoutManager
import kr.toxicity.hud.manager.PlayerManagerImpl
import kr.toxicity.hud.pack.PackGenerator
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.manager.EncodeManager
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.player.SubjectHudPlayer
import kr.toxicity.hud.resource.GlobalResource
import kr.toxicity.hud.util.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HudImpl(
    override val id: String,
    resource: GlobalResource,
    section: YamlObject
) : Hud, HudConfiguration, PlaceholderSource by PlaceholderSource.Impl(section) {

    private var imageChar = 0xCE000

    val newChar
        get() = (++imageChar).parseChar()

    private val imageEncoded = "hud_${id}_image".encodeKey(EncodeManager.EncodeNamespace.FONT)
    val imageKey = createAdventureKey(imageEncoded)
    var jsonArray: JsonArray? = JsonArray()
    private val spaces = intKeyMapOf<String>()
    private val default = ConfigManagerImpl.defaultHud.contains(id) || section.getAsBoolean("default", false)
    var textIndex = 0
    private val tick = section.getAsLong("tick", 1)

    /** The proxy of one viewer, together with the player object it was built from. */
    private class SubjectEntry(val player: HudPlayer, val proxy: SubjectHudPlayer)

    private val subjectPlayers = ConcurrentHashMap<UUID, SubjectEntry>()

    /** When the table was last scanned; the scan is O(entries) and must not run per mount. */
    @Volatile
    private var lastPrune = 0L

    /**
     * The proxy of this viewer. It is reused across updates - the renderers key their tween state by
     * the player they are given, so a proxy that changes would restart every smooth element - but never
     * across a player object: BetterHud rebuilds a HudPlayer on rejoin or reload, and a proxy still
     * holding the old one keeps answering with its data (placeholders reading through the HudPlayer
     * stop following, while those reading `handle()` keep working). The entries of players who left go
     * once the table grows.
     */
    private fun subjectPlayerOf(player: HudPlayer): SubjectHudPlayer {
        subjectPlayers[player.uuid()]?.let {
            if (it.player === player) return it.proxy
        }
        if (subjectPlayers.size >= PRUNE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastPrune >= PRUNE_INTERVAL_MILLIS) {
                lastPrune = now
                subjectPlayers.keys.removeIf { PlayerManagerImpl.getHudPlayer(it) == null }
            }
        }
        return subjectPlayers.compute(player.uuid()) { _, _ ->
            SubjectEntry(player, SubjectHudPlayer(this, player))
        }!!.proxy
    }

    private companion object {
        const val PRUNE_THRESHOLD = 64
        const val PRUNE_INTERVAL_MILLIS = 30_000L
    }

    /** The `observer` block of this hud, read from its own yml (see [ObserverOption]). */
    internal val observerOption = section["observer"]?.asObject()?.let {
        ObserverOption.parse(this, it)
    }

    fun getOrCreateSpace(int: Int): String = spaces.computeIfAbsent(int) {
        newChar
    }

    private val elements = section["layouts"]?.asObject().ifNull { "layout configuration not set." }.mapSubConfiguration { s, yamlObject ->
        val layout = yamlObject["name"]?.asString().ifNull { "name value not set: $s" }.let {
            LayoutManager.getLayout(it).ifNull { "this layout doesn't exist: $it" }
        }
        var gui = GuiLocation(yamlObject)
        yamlObject["gui"]?.let {
            gui += it.toGuiLocation()
        }
        val pixel = yamlObject["pixel"]?.toPixelLocation() ?: PixelLocation.zero
        HudAnimation(
            s,
            layout.animation.type,
            layout.animation.location.map {
                HudParser(
                    this@HudImpl,
                    resource,
                    layout,
                    gui,
                    it + pixel
                )
            }
        )
    }.ifEmpty {
        throw RuntimeException("layout is empty.")
    }
    init {
        jsonArray?.let { array ->
            if (spaces.isNotEmpty()) array += jsonObjectOf(
                "type" to "space",
                "advances" to jsonObjectOf(*spaces.map {
                    it.value to it.key
                }.toTypedArray())
            )
            PackGenerator.addTask(resource.font + "$imageEncoded.json") {
                jsonObjectOf("providers" to array).toByteArray()
            }
        }
        jsonArray = null
    }


    override fun getType(): HudObjectType<*> {
        return HudObjectType.HUD
    }

    override fun tick(): Long = tick

    private val conditions = section.toConditions(this) build UpdateEvent.EMPTY

    override fun createRenderer(player: HudPlayer): HudComponentSupplier<Hud> {
        // The managing plugin may name another player as this hud's data owner (an observer seeing
        // the watched player's values) - the tick, the output and the hidden entries stay the
        // viewer's. Only huds which actually switch data are wrapped: the wrapper is a different
        // object, and BetterHud keys its interpolation state by player identity (a hud wrapped for
        // nothing would lose every tween it has).
        val renderPlayer = if (HudViewManager.usesSubject(this)) subjectPlayerOf(player) else player
        val map = elements.map { animation ->
            animation to animation.elements.map { p ->
                runByTick(tick, { renderPlayer.tick }, p.getComponent(renderPlayer))
            }
        }
        return HudComponentSupplier.of(this) {
            if (conditions(renderPlayer)) {
                val hidden = HudViewManager.hiddenEntriesOf(this, player)
                map.mapNotNull { (animation, runners) ->
                    if (animation.id in hidden) null
                    else animation.animationType.choose(runners, renderPlayer.tick)()
                }
            } else emptyList()
        }
    }

    override fun getName(): String = id
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HudImpl

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun isDefault(): Boolean = default

    /** One entry of `layouts`: the key it is declared under (what `hiddenEntriesOf` refers to). */
    private class HudAnimation(
        val id: String,
        val animationType: AnimationType,
        val elements: List<HudParser>
    )
}