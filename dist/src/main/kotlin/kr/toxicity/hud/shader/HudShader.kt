package kr.toxicity.hud.shader

import kr.toxicity.hud.location.GuiLocation

data class HudShader(
    val gui: GuiLocation,
    val renderScale: RenderScale,
    val layer: Int,
    val outline: Int,
    val opacity: Double,
    val property: Int,
    val rotationMode: Int = 0,
    val rotationDegree: Double = 0.0,
    val rotationHalfX: Double = 0.0,
    val rotationHalfY: Double = 0.0,
) : Comparable<HudShader> {
    companion object {
        private val comparator = Comparator.comparing { s: HudShader ->
            s.gui
        }.thenComparing { s: HudShader ->
            s.renderScale
        }.thenComparing { s: HudShader ->
            s.layer
        }.thenComparing { s: HudShader ->
            s.outline
        }.thenComparing { s: HudShader ->
            s.opacity
        }.thenComparing { s: HudShader ->
            s.property
        }.thenComparingInt { s: HudShader ->
            s.rotationMode
        }.thenComparingDouble { s: HudShader ->
            s.rotationDegree
        }.thenComparingDouble { s: HudShader ->
            s.rotationHalfX
        }.thenComparingDouble { s: HudShader ->
            s.rotationHalfY
        }
    }

    override fun compareTo(other: HudShader): Int {
        return comparator.compare(this, other)
    }

    fun toBackground(otherOpacity: Double) = HudShader(
        gui,
        renderScale,
        layer,
        0,
        opacity * otherOpacity,
        property
    )

    fun toFancyHead() = HudShader(
        gui,
        renderScale * 1.125,
        layer + 1,
        0x8F shl 24,
        opacity,
        property
    )
}