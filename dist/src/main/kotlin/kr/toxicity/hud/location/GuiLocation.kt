package kr.toxicity.hud.location

import kr.toxicity.hud.api.yaml.YamlObject

data class GuiLocation(val x: Double, val y: Double) : Comparable<GuiLocation> {
    companion object {
        private val comparator = Comparator.comparing { gui: GuiLocation ->
            gui.x
        }.thenComparing { gui: GuiLocation ->
            gui.y
        }

        private val space = "\\s+".toRegex()

        private fun YamlObject.at(index: Int): Double {
            val at = get("at") ?: return getAsDouble(if (index == 0) "x" else "y", 0.0)
            return runCatching {
                at.asArray().elementAtOrNull(index)?.asString()?.toDoubleOrNull()
            }.getOrNull()
                ?: at.asString().trim().split(space).getOrNull(index)?.toDoubleOrNull()
                ?: 0.0
        }
    }

    constructor(section: YamlObject): this(
        section.at(0).coerceAtLeast(0.0).coerceAtMost(100.0),
        section.at(1).coerceAtLeast(0.0).coerceAtMost(100.0)
    )
    operator fun plus(other: GuiLocation) = GuiLocation(x + other.x, y + other.y)
    override fun compareTo(other: GuiLocation): Int = comparator.compare(this, other)
}