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

/**
 * Variables of one instance.
 *
 * `variables` are the constants of the whole repeat, [entry] (from `entries`) is the data of *this*
 * instance - so an entry wins over both the row offset `{y}` and the constants. The instance number
 * (`as`, default `i`, one-based) is bound last and therefore wins over everything: an instance can
 * always be asked which instance it is.
 */
internal fun repeatVariables(
    index: Int,
    name: String,
    y: Int,
    variables: Map<String, Any>,
    entry: Map<String, Any> = emptyMap()
): Map<String, Any> = mapOf("y" to y) + variables + entry + (name to (index + 1))

class RepeatLayout(
    val id: String,
    val sender: BetterCommandSource,
    private val section: YamlObject
) : ConditionSource by ConditionSource.Impl(section), PlaceholderSource by PlaceholderSource.Impl(section) {

    private val sourceValue: String? = section["source"]?.asString()
    /**
     * How many of the [max] instances to render, clamped into `0..max` at run time.
     *
     * Optional **when [entries] is set**: without it every instance is rendered, which is what a
     * table of heterogeneous instances wants (each one carries its own conditions).
     */
    val source: PlaceholderBuilder<*>? = sourceValue?.let {
        PlaceholderManagerImpl.find(it, this).assertNumber { "this source is not a number: $it" }
    }

    /**
     * Per-instance data (`entries`): a list of maps, one map per instance.
     *
     * Each entry is substituted like `variables` is, only for its own instance - so one template can
     * describe instances that differ in anything a string can carry (image name, coordinates,
     * condition bounds, an offset expression, ...). Values are read as strings, exactly like
     * `variables`; the instance itself converts a numeric looking string back to a number.
     *
     * With `entries` the number of instances is the size of the list and `max` must not be set.
     */
    private val entries: List<Map<String, Any>> = section["entries"]?.asArray()?.mapNotNull { element ->
        runCatching {
            LinkedHashMap<String, Any>().also { target ->
                element.asObject().forEach { target[it.key] = it.value.asString() }
            }
        }.getOrNull()
    } ?: emptyList()

    private val maxValue: Int = section.getAsInt("max", -1)

    /** Number of compiled instances: `entries.size`, or `max`. */
    val max: Int = if (entries.isNotEmpty()) entries.size else maxValue.also {
        if (it < 1) throw RuntimeException("max must be at least 1: $id")
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
            entries.isNotEmpty() && section["max"] != null -> throw RuntimeException("max and entries are both set: $id")
            entries.isEmpty() && source == null -> throw RuntimeException("source value not set: $id")
            positions > 1 -> throw RuntimeException("only one of grid, flow, offset and equation is allowed: $id")
            // With `entries` no positioning key is needed: every instance sits on the group origin and
            // carries its own coordinates inside the template (`x` / `y` / `position`).
            positions == 0 && entries.isEmpty() ->
                throw RuntimeException("neither grid, flow, offset nor equation is set: $id")
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
                repeatVariables(
                    index,
                    variable,
                    origin(index).y,
                    variables,
                    entries.getOrElse(index) { emptyMap() }
                ),
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
