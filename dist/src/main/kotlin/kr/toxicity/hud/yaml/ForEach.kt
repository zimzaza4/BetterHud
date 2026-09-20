package kr.toxicity.hud.yaml

import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.util.toYaml

private const val FOR_EACH = "for-each"
private val INTEGER = Regex("-?\\d+")

fun YamlObject.forEachExpanded(block: (String, YamlObject) -> Unit) {
    get().forEach { (key, value) ->
        val loop = (value as? Map<*, *>)?.get(FOR_EACH)
        if (loop == null) {
            (value.toYaml(key) as? YamlObject)?.let {
                block(key, it)
            }
        } else {
            combinations(loop).forEach { variables ->
                val name = key.fill(variables)
                val body = LinkedHashMap<String, Any>()
                value.forEach { (k, v) ->
                    if (k != null && v != null && k.toString() != FOR_EACH) {
                        body[k.toString().fill(variables)] = v.substitute(variables)
                    }
                }
                block(name, YamlObjectImpl(name, body))
            }
        }
    }
}

private fun combinations(loop: Any): List<Map<String, Any>> = when (loop) {
    is String -> range(loop)?.map {
        mapOf("i" to it)
    } ?: throw RuntimeException("invalid for-each range: $loop")
    is List<*> -> {
        val list = loop.filterNotNull()
        if (list.all { it is Map<*, *> }) list.map { record ->
            LinkedHashMap<String, Any>().also { target ->
                (record as Map<*, *>).forEach { (k, v) ->
                    if (k != null && v != null) target[k.toString()] = v
                }
            }
        } else list.map {
            mapOf("i" to it)
        }
    }
    is Map<*, *> -> loop.entries.fold(listOf<Map<String, Any>>(emptyMap())) { accumulator, entry ->
        val name = entry.key?.toString() ?: return@fold accumulator
        val values = values(entry.value)
        accumulator.flatMap { base ->
            values.map {
                base + (name to it)
            }
        }
    }
    else -> throw RuntimeException("invalid for-each value: $loop")
}

private fun values(spec: Any?): List<Any> = when (spec) {
    null -> emptyList()
    is List<*> -> spec.filterNotNull()
    is String -> range(spec) ?: listOf(spec)
    else -> listOf(spec)
}

private fun range(value: String): List<Int>? {
    val split = value.split("..")
    if (split.size != 2) return null
    val from = split[0].trim().toIntOrNull() ?: return null
    val to = split[1].trim().toIntOrNull() ?: return null
    return if (from <= to) (from..to).toList() else (from downTo to).toList()
}

private fun Any.substitute(variables: Map<String, Any>): Any = when (this) {
    is Map<*, *> -> LinkedHashMap<String, Any>().also { target ->
        forEach { (k, v) ->
            if (k != null && v != null) {
                target[k.toString().fill(variables)] = v.substitute(variables)
            }
        }
    }
    is List<*> -> mapNotNull {
        it?.substitute(variables)
    }
    is String -> {
        val filled = fill(variables)
        if (filled != this && INTEGER.matches(filled)) filled.toInt() else filled
    }
    else -> this
}

private fun String.fill(variables: Map<String, Any>): String {
    var result = this
    variables.forEach { (name, value) ->
        result = result.replace("{$name}", value.toString())
    }
    return result
}

fun YamlObject.substituted(variables: Map<String, Any>, overrides: Map<String, Any> = emptyMap()): YamlObject {
    val body = LinkedHashMap<String, Any>()
    get().forEach { (key, value) ->
        if (value != null) {
            body[key.toString().fill(variables)] = value.substitute(variables)
        }
    }
    body.putAll(overrides)
    return YamlObjectImpl("", body)
}
