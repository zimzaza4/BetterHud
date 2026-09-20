package kr.toxicity.hud.location

import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.manager.ConfigManagerImpl

data class PixelLocation(val x: Int, val y: Int, val opacity: Double) : Comparable<PixelLocation> {
    companion object {
        private fun Double.asColor() = coerceAtLeast(0.0).coerceAtMost(1.0)
        private val comparator = Comparator.comparingInt { image: PixelLocation ->
            image.x
        }.thenComparingInt { image: PixelLocation ->
            image.y
        }.thenComparingDouble { image: PixelLocation ->
            image.opacity
        }

        private val _hotBarHeight = PixelLocation(0, -54, 1.0)
        val hotBarHeight
            get() = if (ConfigManagerImpl.disableLegacyOffset) zero else _hotBarHeight
        val zero = PixelLocation(0, 0, 1.0)

        private val space = "\\s+".toRegex()

        private fun YamlObject.at(index: Int): Int {
            val at = get("at") ?: return getAsInt(if (index == 0) "x" else "y", 0)
            return runCatching {
                at.asArray().elementAtOrNull(index)?.asString()?.toIntOrNull()
            }.getOrNull()
                ?: at.asString().trim().split(space).getOrNull(index)?.toIntOrNull()
                ?: 0
        }
    }
    constructor(section: YamlObject): this(
        section.at(0),
        section.at(1),
        section.getAsDouble("opacity", 1.0).asColor()
    )
    operator fun plus(other: PixelLocation) = PixelLocation(x + other.x, y + other.y, (opacity * other.opacity).asColor())
    override fun compareTo(other: PixelLocation): Int = comparator.compare(this, other)
}