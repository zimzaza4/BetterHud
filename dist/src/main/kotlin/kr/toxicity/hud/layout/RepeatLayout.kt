package kr.toxicity.hud.layout

import kr.toxicity.command.BetterCommandSource
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.equation.EquationTriple
import kr.toxicity.hud.equation.TEquation
import kr.toxicity.hud.layout.enums.RepeatAlign
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.manager.LayoutManager
import kr.toxicity.hud.manager.PlaceholderManagerImpl
import kr.toxicity.hud.placeholder.ConditionSource
import kr.toxicity.hud.placeholder.PlaceholderBuilder
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.util.*
import kr.toxicity.hud.yaml.substituted

internal fun repeatPitch(width: Int?, space: Int, dx: Int): Int = width?.let {
    it + space
} ?: dx

internal fun repeatShift(count: Int, pitch: Int, align: RepeatAlign): Int = if (count <= 1) 0 else when (align) {
    RepeatAlign.START -> 0
    RepeatAlign.CENTER -> -((count - 1) * pitch) / 2
    RepeatAlign.END -> -((count - 1) * pitch)
}

internal fun gridOrigin(index: Int, perRow: Int, xSpace: Int, ySpace: Int): PixelLocation =
    PixelLocation((index % perRow) * xSpace, (index / perRow) * ySpace, 1.0)

internal fun gridRowCount(index: Int, count: Int, perRow: Int): Int =
    (count - (index / perRow) * perRow).coerceIn(0, perRow)

internal fun repeatVariables(index: Int, name: String, y: Int, variables: Map<String, Any>): Map<String, Any> =
    mapOf("y" to y) + variables + (name to (index + 1))

class RepeatLayout(
    val id: String,
    val sender: BetterCommandSource,
    private val section: YamlObject
) : ConditionSource by ConditionSource.Impl(section), PlaceholderSource by PlaceholderSource.Impl(section) {

    private val sourceValue: String = section["source"]?.asString().ifNull { "source value not set: $id" }
    val source: PlaceholderBuilder<*> = PlaceholderManagerImpl.find(sourceValue, this).assertNumber {
        "this source is not a number: $sourceValue"
    }

    val max: Int = section.getAsInt("max", -1).apply {
        if (this < 1) throw RuntimeException("max must be at least 1: $id")
    }

    val variable: String = section.getAsString("as", "i")

    val variables: Map<String, Any> = section["variables"]?.asObject()?.let { source ->
        LinkedHashMap<String, Any>().also { target ->
            source.forEach {
                target[it.key] = it.value.asString()
            }
        }
    } ?: emptyMap()

    private val template: String? = section["template"]?.asString()
    private val pixel = section["pixel"]?.toSection()
    private val equation: EquationTriple? = pixel?.takeIf {
        it["x-equation"] != null || it["y-equation"] != null
    }?.let {
        EquationTriple(
            it["x-equation"]?.asString()?.toEquation() ?: TEquation.zero,
            it["y-equation"]?.asString()?.toEquation() ?: TEquation.zero,
            it["opacity-equation"]?.asString()?.toEquation() ?: TEquation.one
        )
    }
    val gui: GuiLocation = section["gui"]?.toGuiLocation() ?: GuiLocation(0.0, 0.0)
    private val flow: Flow? = section["flow"]?.asObject()?.let { Flow(it) }
    private val offset: Offset? = section["offset"]?.asObject()?.let { Offset(it) }
    private val grid: Grid? = section["grid"]?.asObject()?.let { Grid(it) }
    private val align: RepeatAlign = section["align"]?.asString()?.let {
        runCatching {
            RepeatAlign.valueOf(it.uppercase())
        }.onFailure {
            it.handle(sender, "Unable to find that align: $it")
        }.getOrNull()
    } ?: RepeatAlign.START

    init {
        val inline = listOf("images", "texts", "heads").any { section[it] != null }
        val positions = listOf(flow, offset, equation, grid).count { it != null }
        when {
            template != null && inline -> throw RuntimeException("template and inline elements are both set: $id")
            template == null && !inline -> throw RuntimeException("neither template nor inline elements is set: $id")
            positions > 1 -> throw RuntimeException("only one of grid, flow, offset and equation is allowed: $id")
            positions == 0 -> throw RuntimeException("neither grid, flow, offset nor equation is set: $id")
        }
    }

    private val pitch: Int = repeatPitch(flow?.width, flow?.space ?: 0, offset?.dx ?: 0)

    fun location(index: Int): PixelLocation = equation?.let {
        val evaluate = it evaluate (index + 1).toDouble()
        PixelLocation(evaluate.first.toInt(), evaluate.second.toInt(), evaluate.third)
    } ?: pixel?.let {
        PixelLocation(it)
    } ?: PixelLocation.zero

    fun origin(index: Int): PixelLocation = when {
        grid != null -> gridOrigin(index, grid.perRow, grid.xSpace, grid.ySpace)
        flow != null || equation != null -> PixelLocation(index * pitch, 0, 1.0)
        else -> PixelLocation(index * (offset?.dx ?: 0), index * (offset?.dy ?: 0), 1.0)
    }

    fun shift(index: Int, count: Int): Int = if (grid != null) {
        repeatShift(gridRowCount(index, count, grid.perRow), grid.xSpace, align)
    } else repeatShift(count, pitch, align)

    fun instance(index: Int): LayoutGroup {
        val name = template
        val raw = if (name != null) {
            LayoutManager.getTemplate(name).ifNull { "this template doesn't exist: $name in $id" }
        } else section
        return LayoutGroup(
            name ?: id,
            sender,
            raw.substituted(
                repeatVariables(index, variable, origin(index).y, variables),
                mapOf("offset" to "left")
            )
        )
    }

    private class Flow(yamlObject: YamlObject) {
        val width = yamlObject.getAsInt("width", -1).apply {
            if (this < 1) throw RuntimeException("flow width must be at least 1.")
        }
        val space = yamlObject.getAsInt("space", 0)
    }

    private class Offset(yamlObject: YamlObject) {
        val dx = yamlObject.getAsInt("dx", 0)
        val dy = yamlObject.getAsInt("dy", 0)
    }

    private class Grid(yamlObject: YamlObject) {
        val perRow = yamlObject.getAsInt("per-row", -1).apply {
            if (this < 1) throw RuntimeException("grid per-row must be at least 1.")
        }
        val xSpace = yamlObject.getAsInt("x-space", 0)
        val ySpace = yamlObject.getAsInt("y-space", 0)
    }
}
