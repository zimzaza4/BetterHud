package kr.toxicity.hud.shader

import kr.toxicity.hud.location.GuiLocation

data class HudShader(
    val gui: GuiLocation,
    val renderScale: RenderScale,
    val layer: Int,
    val outline: Int,
    val opacity: Double,
    val property: Int,
    val rotationDegree: Double = 0.0,
    val rotationDynamic: Boolean = false,
    val positionDynamic: Boolean = false,
    val rotationHalfX: Double = 0.0,
    val rotationHalfY: Double = 0.0,
    // The offset in `position` is expressed in the element's rotated frame (rotated together with
    // `rotation`). Formerly named `position-in-map-space`: BetterHud has no notion of a "map", that
    // name leaked in from a minimap use case.
    val positionInRotatedSpace: Boolean = false,
    val clipInner: Double = 0.0,
    val clipOuter: Double = 0.0,
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
        }.thenComparing { s: HudShader ->
            s.rotationDynamic
        }.thenComparing { s: HudShader ->
            s.positionDynamic
        }.thenComparingDouble { s: HudShader ->
            s.rotationDegree
        }.thenComparingDouble { s: HudShader ->
            s.rotationHalfX
        }.thenComparingDouble { s: HudShader ->
            s.rotationHalfY
        }.thenComparing { s: HudShader ->
            s.positionInRotatedSpace
        }.thenComparingDouble { s: HudShader ->
            s.clipInner
        }.thenComparingDouble { s: HudShader ->
            s.clipOuter
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