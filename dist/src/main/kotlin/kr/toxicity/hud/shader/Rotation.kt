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
     * Red/green hold the value a boundary moves from and to, blue the tick it was sent for.
     *
     * Three channels are all a glyph has (Minecraft takes the text alpha from the caller, not from
     * the component colour), so one element can carry one tweened value - which is what a filling
     * bar, a growing ring or a fading panel needs.
     */
    const val SMOOTH_VALUE = 4

    /**
     * Red/green hold an 11 bit angle and a 5 bit tick, blue the angular rate.
     *
     * The rate lets the client keep turning between updates, so a placeholder driven rotation looks
     * continuous instead of jumping once per element tick. Unlike [ROTATION] this one keeps the
     * element's colour, because the angle moved into fewer bits.
     */
    const val SMOOTH_ROTATION = 5

    /**
     * Red/green/blue hold two ten bit endpoints of one tween and the four bit tick it belongs to.
     *
     * The layout a content scroll uses. Unlike [SMOOTH_ROTATION] it carries no rate: the client walks to a
     * known endpoint and lands on it, so a change of speed cannot make it lurch past. Ten bits keep a strip
     * usable - eight would be 2.8 pixels of a 720 pixel field.
     */
    const val SMOOTH_TWEEN = 6

    /**
     * Red holds an eight bit angle; green/blue hold a six bit `from`, a six bit `to` and the four bit tick
     * they were sent for.
     *
     * The one pair no other layout has room for: a region which fills while its element turns. The angle
     * is stepped rather than tweened - eight bits of it is what the sixteen left over allow - so it suits a
     * fan which jumps to a new angle, not a fast spin.
     */
    const val SMOOTH_MIXED = 7

    /**
     * Picks the layout for the given element. The priority itself lives in one place, [PayloadOwner.of].
     * @param rotationDynamic whether the angle is evaluated per update
     * @param positionDynamic whether the offset is evaluated per update
     * @param rateSmooth whether the element wants the rate carrying layout (a tweened rotation)
     * @param valueSmooth whether `clip-fill` is tweened between updates
     * @param tweenSmooth whether a content scroll is tweened between updates (two known endpoints)
     * @return layout
     */
    fun of(
        rotationDynamic: Boolean,
        positionDynamic: Boolean,
        rateSmooth: Boolean,
        valueSmooth: Boolean,
        tweenSmooth: Boolean = false,
        rotationWide: Boolean = false
    ) = PayloadOwner.of(
        rotationDynamic,
        positionDynamic,
        rateSmooth,
        valueSmooth,
        // The older signatures cannot tell a scroll from a narrow angle tween; both take SMOOTH_TWEEN,
        // so the merged flag is read as the scroll the owner table lists first of the two.
        scrollSmooth = tweenSmooth,
        rotationTween = false,
        rotationWide = rotationWide
    ).kind

    /**
     * Picks the layout for the given element.
     * @param rotationDynamic whether the angle is evaluated per update
     * @param positionDynamic whether the offset is evaluated per update
     * @return layout
     */
    fun of(rotationDynamic: Boolean, positionDynamic: Boolean) =
        of(rotationDynamic, positionDynamic, false, false)
}

/**
 * Which of an element's per update values owns the glyph's colour channels.
 *
 * A glyph has three writable channels, so one value can travel between updates - and the case body
 * (which decodes that value) and the renderer (which packs it) have to agree on which. [of] is the only
 * place that decides; [kind] is what the case body emits as `bhPayload`.
 */
enum class PayloadOwner(val kind: Int) {
    /** Nothing changes between updates. */
    NONE(PayloadKind.NONE),

    /** A tweened angle long enough to need the eight bit payload's longer ramp. */
    ROTATION_WIDE(PayloadKind.SMOOTH_VALUE),

    /** A content scroll. */
    SCROLL(PayloadKind.SMOOTH_TWEEN),

    /** A tweened angle carried as two known endpoints. */
    ROTATION_TWEEN(PayloadKind.SMOOTH_TWEEN),

    /** A tweened angle carried as a value and a rate. */
    ROTATION_RATE(PayloadKind.SMOOTH_ROTATION),

    /** The boundary of a `clip-fill` region. */
    FILL(PayloadKind.SMOOTH_VALUE),

    /** A turning element and a filling region at once: an eight bit angle over a tweened value. */
    MIXED(PayloadKind.SMOOTH_MIXED),

    /** The angle alone. */
    ROTATION(PayloadKind.ROTATION),

    /** The offset alone. */
    POSITION(PayloadKind.POSITION),

    /** The angle and the offset together. */
    TRANSFORM(PayloadKind.ROTATION_POSITION);

    companion object {
        fun of(
            rotationDynamic: Boolean,
            positionDynamic: Boolean,
            rateSmooth: Boolean,
            valueSmooth: Boolean,
            scrollSmooth: Boolean = false,
            rotationTween: Boolean = false,
            rotationWide: Boolean = false
        ): PayloadOwner = when {
            // The eight bit payload's longer ramp, for an angle which needs it.
            rotationWide -> ROTATION_WIDE
            // A scroll and a narrow angle tween share SMOOTH_TWEEN; the scroll has always owned it.
            scrollSmooth -> SCROLL
            // A region which fills while the element turns takes the one layout carrying both, at the cost
            // of stepping the angle rather than tweening it.
            valueSmooth && rotationDynamic -> MIXED
            rotationTween -> ROTATION_TWEEN
            rateSmooth -> ROTATION_RATE
            valueSmooth -> FILL
            rotationDynamic && positionDynamic -> TRANSFORM
            rotationDynamic -> ROTATION
            positionDynamic -> POSITION
            else -> NONE
        }
    }
}
