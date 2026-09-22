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

/**
 * How the vertex colour of a glyph carries per-frame data.
 *
 * The channel layout is fixed, so both the packer and the shader agree on it.
 * @see kr.toxicity.hud.util.applyRotationPayload
 * @see kr.toxicity.hud.util.applyPositionPayload
 * @see kr.toxicity.hud.util.applyRotationPositionPayload
 */
object PayloadKind {
    /**
     * The colour is a plain colour.
     */
    const val NONE = 0

    /**
     * Red/green hold a 16 bit angle.
     */
    const val ROTATION = 1

    /**
     * Red/green hold a 12 bit x offset and green/blue a 12 bit y offset.
     */
    const val POSITION = 2

    /**
     * Red holds an 8 bit angle, green an 8 bit x offset and blue an 8 bit y offset.
     */
    const val ROTATION_POSITION = 3

    /**
     * Picks the layout for the given element.
     * @param rotationDynamic whether the angle is evaluated per update
     * @param positionDynamic whether the offset is evaluated per update
     * @return layout
     */
    fun of(rotationDynamic: Boolean, positionDynamic: Boolean) = when {
        rotationDynamic && positionDynamic -> ROTATION_POSITION
        rotationDynamic -> ROTATION
        positionDynamic -> POSITION
        else -> NONE
    }
}
