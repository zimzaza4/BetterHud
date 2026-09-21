package kr.toxicity.hud.shader

import kr.toxicity.hud.placeholder.PlaceholderBuilder

/**
 * How an element is rotated around its own centre.
 *
 * [Static] bakes the angle into the generated shader, so it costs nothing per frame.
 * [Dynamic] packs the angle into the glyph's colour on every update, which lets a placeholder
 * (and therefore any plugin) drive the rotation from the bossbar.
 */
sealed interface Rotation {
    /**
     * No rotation.
     */
    data object None : Rotation

    /**
     * Baked angle in degrees.
     * @param degree angle
     */
    data class Static(val degree: Double) : Rotation

    /**
     * Angle in degrees, evaluated for every player and every update.
     * @param degree placeholder
     */
    data class Dynamic(val degree: PlaceholderBuilder<*>) : Rotation

    /**
     * 0 for none, 1 for static, 2 for dynamic.
     */
    val mode: Int
        get() = when (this) {
            None -> 0
            is Static -> 1
            is Dynamic -> 2
        }

    /**
     * The angle which can be baked into the shader.
     */
    val bakedDegree: Double
        get() = if (this is Static) degree else 0.0
}
