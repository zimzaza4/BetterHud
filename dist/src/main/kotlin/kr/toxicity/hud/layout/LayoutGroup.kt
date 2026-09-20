package kr.toxicity.hud.layout

import kr.toxicity.command.BetterCommandSource
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.configuration.HudConfiguration
import kr.toxicity.hud.layout.enums.LayoutAlign
import kr.toxicity.hud.layout.enums.LayoutOffset
import kr.toxicity.hud.animation.AnimationLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.placeholder.ConditionSource
import kr.toxicity.hud.util.*

class LayoutGroup(
    override val id: String,
    val sender: BetterCommandSource,
    val raw: YamlObject
) : HudConfiguration, ConditionSource by ConditionSource.Impl(raw) {

    private val loc = PixelLocation(raw)

    val align = raw["align"]?.asString()?.let {
        runCatching {
            LayoutAlign.valueOf(it.uppercase())
        }.onFailure {
            it.handle(sender, "Unable to find that align: $it")
        }.getOrNull()
    } ?: LayoutAlign.LEFT
    val offset = raw["offset"]?.asString()?.let {
        runCatching {
            LayoutOffset.valueOf(it.uppercase())
        }.onFailure {
            it.handle(sender, "Unable to find that offset: $it")
        }.getOrNull()
    } ?: LayoutOffset.CENTER

    val image = raw["images"]?.asObject()?.mapSubConfiguration { s, yamlObject ->
        ImageLayout.Impl(s, this, yamlObject, loc)
    } ?: emptyList()
    val text = raw["texts"]?.asObject()?.mapSubConfiguration { s, yamlObject ->
        TextLayout.Impl(s, this, yamlObject, loc)
    } ?: emptyList()
    val head = raw["heads"]?.asObject()?.mapSubConfiguration { s, yamlObject ->
        HeadLayout.Impl(s, this, yamlObject, loc)
    } ?: emptyList()
    val repeat = raw["repeats"]?.asObject()?.mapSubConfiguration { s, yamlObject ->
        RepeatLayout(s, sender, yamlObject)
    } ?: emptyList()

    val animation = raw["animations"]?.asObject()?.let { animations ->
        AnimationLocation(animations)
    } ?: AnimationLocation.zero
}
