package kr.toxicity.hud.placeholder

import kr.toxicity.hud.api.yaml.YamlElement
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.manager.PlaceholderManagerImpl
import kr.toxicity.hud.util.forEachSubConfiguration
import kr.toxicity.hud.util.handleFailure
import kr.toxicity.hud.util.ifNull

object Conditions {
    fun parse(section: YamlObject, source: PlaceholderSource): ConditionBuilder {
        var value: ConditionBuilder = ConditionBuilder.alwaysTrue
        section.forEachSubConfiguration { s, yamlObject ->
            runCatching {
                val new = parse0(yamlObject, source)
                value = when (val gate = yamlObject["gate"]?.asString() ?: "and") {
                    "and" -> value and new
                    "or" -> value or new
                    else -> {
                        throw RuntimeException("this gate doesn't exist: $gate")
                    }
                }
            }.handleFailure {
                "Unable to load this condition: $s"
            }
        }
        return value
    }

    fun parseIf(value: Any?, source: PlaceholderSource): ConditionBuilder = when (value) {
        null -> ConditionBuilder.alwaysTrue
        is YamlElement -> parseIf(value.get(), source)
        is String -> ConditionExpression.parse(value, source)
        is List<*> -> value.fold(ConditionBuilder.alwaysTrue) { builder, element ->
            builder and parseIf(element, source)
        }
        is Map<*, *> -> {
            val all = value["all"]?.let { parseIfAll(it, source) }
            val any = value["any"]?.let { parseIfAny(it, source) }
            when {
                all != null && any != null -> all and any
                all != null -> all
                any != null -> any
                else -> ConditionBuilder.alwaysTrue
            }
        }
        else -> throw RuntimeException("unsupported condition: $value")
    }

    /** `all:` 的值是合取：列表逐项 AND，其它形态直接递归。 */
    private fun parseIfAll(value: Any?, source: PlaceholderSource): ConditionBuilder = when (value) {
        is List<*> -> value.fold(ConditionBuilder.alwaysTrue) { builder, element ->
            builder and parseIf(element, source)
        }
        else -> parseIf(value, source)
    }

    /** `any:` 的值是析取：列表逐项 OR。注意不能用 alwaysTrue 做起点，否则恒为真。 */
    private fun parseIfAny(value: Any?, source: PlaceholderSource): ConditionBuilder = when (value) {
        is List<*> -> value.map { parseIf(it, source) }.reduceOrNull { left, right ->
            left or right
        } ?: ConditionBuilder.alwaysTrue
        else -> parseIf(value, source)
    }

    @Suppress("UNCHECKED_CAST")
    fun compare(first: PlaceholderBuilder<*>, second: PlaceholderBuilder<*>, operationValue: String): ConditionBuilder {
        if (first.clazz != second.clazz) throw RuntimeException("type mismatch: ${first.clazz.simpleName} and ${second.clazz.simpleName}")

        val operation = (Operations.find(first.clazz) ?: throw RuntimeException("unable to load valid operation. you need to call developer."))[operationValue]
            .ifNull { "unsupported operation: $operationValue" } as (Any, Any) -> Boolean
        return ConditionBuilder { updateEvent ->
            val o1 = first build updateEvent
            val o2 = second build updateEvent
            ({ p ->
                operation(o1.value(p), o2.value(p))
            })
        }
    }

    fun hold(text: String, value: PlaceholderBuilder<*>): ConditionBuilder {
        val boolean = value.assertBoolean("this placeholder is not a boolean: $text")
        return ConditionBuilder { updateEvent ->
            val o = boolean build updateEvent
            ({ p ->
                o.value(p) as Boolean
            })
        }
    }

    private fun parse0(section: YamlObject, source: PlaceholderSource): ConditionBuilder {
        val first = PlaceholderManagerImpl.find(section["first"]?.asString().ifNull { "first value not set." }, source)
        val second = PlaceholderManagerImpl.find(section["second"]?.asString().ifNull { "second value not set." }, source)
        return compare(first, second, section["operation"]?.asString().ifNull { "operation value not set" })
    }
}
