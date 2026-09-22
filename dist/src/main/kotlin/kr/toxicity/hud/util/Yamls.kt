package kr.toxicity.hud.util

import kr.toxicity.command.BetterCommandSource
import kr.toxicity.hud.animation.AnimationType
import kr.toxicity.hud.api.yaml.YamlElement
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.equation.TEquation
import kr.toxicity.hud.manager.PlaceholderManagerImpl
import kr.toxicity.hud.placeholder.ColorOverride
import kr.toxicity.hud.placeholder.ConditionBuilder
import kr.toxicity.hud.placeholder.Conditions
import kr.toxicity.hud.placeholder.PlaceholderBuilder
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.shader.Rotation
import kr.toxicity.hud.yaml.YamlArrayImpl
import kr.toxicity.hud.yaml.YamlElementImpl
import kr.toxicity.hud.yaml.YamlObjectImpl
import kr.toxicity.hud.yaml.forEachExpanded
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream

private val YAML = Yaml()

fun Any.toYaml(path: String): YamlElement = when (this) {
    is Map<* ,*> -> YamlObjectImpl(path, LinkedHashMap<String, Any>().also {
        entries.forEach { e ->
            it[(e.key ?: return@forEach).toString()] = e.value ?: return@forEach
        }
    })
    is List<*> -> YamlArrayImpl(path, this)
    else -> YamlElementImpl(path, this)
}

fun File.toYaml(): YamlObject = synchronized(YAML) {
    inputStream().buffered().use {
        YamlObjectImpl(
            "",
            YAML.load(it) ?: mutableMapOf<String, Any>()
        )
    }
}

fun InputStream.toYaml() = synchronized(YAML) {
    YamlObjectImpl(
        "",
        YAML.load(this) ?: mutableMapOf<String, Any>()
    )
}

fun Map<String, Any>.saveToYaml(file: File) {
    synchronized(YAML) {
        file.bufferedWriter().use {
            it.write(YAML.dumpAsMap(this))
        }
    }
}

fun YamlObject.getAsAnimationType(key: String, sender: BetterCommandSource = BOOTSTRAP.consoleSource()) = get(key)?.asString()?.let {
    runCatching {
        AnimationType.valueOf(it.uppercase())
    }.getOrElse { e ->
        e.handle(sender, "This animation doesn't exist.")
        AnimationType.LOOP
    }
} ?: AnimationType.LOOP


fun File.forEachAllYaml(sender: BetterCommandSource, block: (File, String, YamlObject) -> Unit) {
    forEachAllFolder { file ->
        if (file.extension == "yml") {
            runCatching {
                file.toYaml().forEachExpanded { name, yamlObject ->
                    block(file, name, yamlObject)
                }
            }.handleFailure(sender) {
                "Unable to load this yml file: ${file.name}"
            }
        } else {
            sender.warn("This is not a yml file: ${file.path}")
        }
    }
}

fun YamlObject.toConditions(source: PlaceholderSource) = Conditions.parseIf(get("if"), source) and (get("conditions")?.asObject()?.let {
    Conditions.parse(it, source)
} ?: ConditionBuilder.alwaysTrue)
fun YamlObject.toColorOverrides(source: PlaceholderSource) = get("color-overrides")?.asObject()?.let {
    ColorOverride.builder(it, source)
} ?: ColorOverride.empty

/**
 * Reads `rotation:`.
 *
 * A plain number is a baked (static) angle; anything else is treated as a numeric placeholder
 * which is evaluated for every player on every update.
 */
fun YamlObject.toRotation(source: PlaceholderSource): Rotation {
    val raw = get("rotation")?.asString() ?: return Rotation.None
    raw.toDoubleOrNull()?.let {
        return Rotation.Static(it)
    }
    return Rotation.Dynamic(
        PlaceholderManagerImpl.find(raw, source).assertNumber("this rotation is not a number: $raw")
    )
}

/**
 * Reads one axis of `position: [x, y]`.
 *
 * Both entries are number placeholders which are evaluated for every player on every update and
 * become a pixel offset that the vertex shader applies to the glyph.
 * @param source placeholder source
 * @param index 0 for x, 1 for y
 */
fun YamlObject.toPosition(source: PlaceholderSource, index: Int): PlaceholderBuilder<*>? {
    val raw = get("position")?.asArray()?.elementAtOrNull(index)?.asString() ?: return null
    return PlaceholderManagerImpl.find(raw, source).assertNumber("this position is not a number: $raw")
}

fun YamlObject.getTEquation(key: String) = get(key)?.asString()?.let {
    TEquation(it)
}

fun YamlObject.getShadow(key: String) = when (val value = getAsString(key, "0")) {
    "false" -> 0
    "true" -> 0xFF shl 24
    else -> (value.toLong(16) and 0xFFFFFFFF).toInt()
}