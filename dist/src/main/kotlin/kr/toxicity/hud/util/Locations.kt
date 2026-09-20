package kr.toxicity.hud.util

import kr.toxicity.hud.api.yaml.YamlElement
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.yaml.YamlObjectImpl

fun Any?.toSection(): YamlObject = when (this) {
    null -> YamlObjectImpl.empty
    is YamlObject -> this
    else -> (this as? YamlElement)?.let {
        runCatching {
            it.asObject()
        }.getOrNull()
    } ?: YamlObjectImpl("", linkedMapOf("at" to this))
}

fun Any?.toPixelLocation(): PixelLocation = PixelLocation(toSection())

fun Any?.toGuiLocation(): GuiLocation = GuiLocation(toSection())
