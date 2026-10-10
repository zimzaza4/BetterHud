package kr.toxicity.hud.shader

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The custom clip region of an element (`clip-rect`): a rectangle in the element's own pixels,
 * optionally rounded and feathered, which the shader intersects with the glyph box.
 *
 * @param x left edge, from the element's left
 * @param y top edge, from the element's top
 * @param width width of the region
 * @param height height of the region
 * @param radius corner radius (0 = square corners)
 * @param soft feather width in pixels (0 = a hard discard)
 * @param axis what the region is filled along: [AXIS_X], [AXIS_Y] or [AXIS_RADIAL]
 * @param invert fill from the opposite edge
 * @param followsContent whether the region travels with the content (a `scroll` or a rotation), or stays
 * where the element's placement put it. The default is the fixed window: the rectangle is then measured
 * in the element's own box and never moves, so a scrolling strip passes behind it - what a minimap does.
 * `clip-follow: content` opts into the other reading, where the rectangle is a stencil on the picture.
 */
data class ClipRegion(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val radius: Double = 0.0,
    val soft: Double = 0.0,
    val axis: Int = NONE,
    val invert: Boolean = false,
    val followsContent: Boolean = false,
    /**
     * Half the size of the element the region belongs to, in the units the shader measures the local
     * coordinate in. The shader's `bhD` is the vertex relative to the element's centre, so
     * `bhD + elementHalf` puts the origin at the element's top-left - which is what `clip-rect` counts
     * from, and the same for every tile of a cut image (a tile only knows its own half, hence the
     * separate value).
     */
    val elementHalfX: Double = 0.0,
    val elementHalfY: Double = 0.0
) : Comparable<ClipRegion> {
    companion object {
        /**
         * The region is not filled: it just clips, so the whole rectangle stays visible.
         */
        const val NONE = -1

        /**
         * The boundary moves along x (a horizontal bar).
         */
        const val AXIS_X = 0

        /**
         * The boundary moves along y (a vertical bar).
         */
        const val AXIS_Y = 1

        /**
         * The region's radius grows with the value (a disc or ring).
         */
        const val AXIS_RADIAL = 2

        /**
         * Both edges move outwards from the middle: a bar which grows from its own centre.
         */
        const val AXIS_CENTER_X = 3

        /**
         * The vertical twin of [AXIS_CENTER_X].
         */
        const val AXIS_CENTER_Y = 4

        /**
         * The region is swept like a clock hand around the element's centre, starting at twelve
         * o'clock: the value is the swept angle (a cooldown or reload ring). `invert` sweeps the other
         * way round.
         */
        const val AXIS_PIE = 5

        // How `clip-fill.value` is read. AUTO is the historical guess - above one is a percentage - which
        // cannot tell `1` (one percent) from `1` (the whole region), so a value sweeping through (0, 1]
        // jumps to the tops of the range whenever a sample lands in it.
        const val UNIT_AUTO = 0
        const val UNIT_PERCENT = 1
        const val UNIT_FRACTION = 2

        fun unitOf(name: String): Int = when (name.lowercase()) {
            "percent", "%" -> UNIT_PERCENT
            "fraction", "ratio" -> UNIT_FRACTION
            else -> UNIT_AUTO
        }
    }

    override fun compareTo(other: ClipRegion): Int = compareValuesBy(
        this, other, { it.x }, { it.y }, { it.width }, { it.height },
        { it.radius }, { it.soft }, { it.axis }, { it.invert }, { it.followsContent },
        { it.elementHalfX }, { it.elementHalfY }
    )
}

/**
 * How an element's values move to a new one between updates (`smooth`).
 *
 * The shader evaluates the curve every frame, so a value which only changes every `tick` still looks
 * continuous. `duration` and `easing` are baked into the element's case body (they change per element,
 * not per frame); the endpoints and the current time travel in the payload.
 *
 * @param duration tween length in ticks
 * @param easing see [EASINGS]
 * @param omegaScale radians per tick at the payload's full angular rate (rotation smoothing)
 */
data class SmoothConfig(
    val duration: Double,
    val easing: Int = SMOOTHSTEP,
    val omegaScale: Double = 0.0
) : Comparable<SmoothConfig> {
    companion object {
        const val SMOOTHSTEP = 0
        const val LINEAR = 1
        const val EASE_OUT = 2

        /**
         * Easing names accepted by the config.
         */
        val EASINGS = listOf("smoothstep", "linear", "ease-out")

        fun easingOf(name: String): Int = when (name.lowercase()) {
            "linear" -> LINEAR
            "ease-out", "easeout" -> EASE_OUT
            else -> SMOOTHSTEP
        }

        /**
         * The easing curve, mirroring `bhEase` in `text.vsh`. The shader and the server both have to walk
         * the same curve, or the point a continuous input retargets from is not the one being drawn.
         */
        fun ease(t: Double, mode: Int): Double = when (mode) {
            LINEAR -> t
            EASE_OUT -> 1.0 - (1.0 - t) * (1.0 - t)
            else -> t * t * (3.0 - 2.0 * t)
        }
    }

    override fun compareTo(other: SmoothConfig): Int =
        compareValuesBy(this, other, { it.duration }, { it.easing }, { it.omegaScale })
}

/**
 * A content scroll driven by a placeholder (`scroll`): the value moves the element's content along one
 * axis, and the shader walks it to the next value between updates.
 *
 * The value repeats with the content, so the endpoints name the branch the element is already in and the
 * shader takes the short way round a whole period (`scroll` pairs with `bhPayload = 6`).
 *
 * @param axis [AXIS_X] or [AXIS_Y]
 * @param scale pixels the payload's full range means (the step is 2 * scale / 1023 pixels)
 */
data class ScrollConfig(
    val axis: Int,
    val scale: Double
) : Comparable<ScrollConfig> {
    companion object {
        const val AXIS_X = 0
        const val AXIS_Y = 1
    }

    override fun compareTo(other: ScrollConfig): Int =
        compareValuesBy(this, other, { it.axis }, { it.scale })
}

/**
 * One value's transition, kept per player and per element.
 *
 * Implements the three rules a GPU-side tween needs (see the payload layout in `text.vsh`):
 * a retarget continues from the value the curve has *now* instead of jumping, a finished transition
 * collapses to `from == to`, and the endpoints are quantized to the payload's grid so both sides
 * compute the same numbers.
 */
class SmoothValue(
    private var from: Double = 0.0,
    private var to: Double = 0.0,
    private var sentTick: Int = 0
) {
    /**
     * Moves towards [target]. [tick] is the world time in ticks, [duration] the tween length in ticks and
     * [quantize] the payload's step count (255 for the colour payload). [easing] has to be the curve the
     * shader renders, or the point a retarget starts from is not the one being drawn.
     */
    fun step(
        target: Double,
        tick: Int,
        duration: Double,
        quantize: Int,
        easing: Int = SmoothConfig.SMOOTHSTEP
    ): SmoothValue {
        if (target != to) {
            from = quantize(curveAt(tick, duration, easing), quantize)
            to = quantize(target, quantize)
            sentTick = tick
        } else if (duration <= 0.0 || wrapped(tick - sentTick) >= duration) {
            // The transition is over: collapsing to `from == to` keeps the wrapped tick counter from
            // replaying a finished animation every time it rolls over.
            from = to
        }
        return this
    }

    /**
     * The value which is visible right now - used on the server side to retarget without a jump.
     */
    fun curveAt(tick: Int, duration: Double, easing: Int = SmoothConfig.SMOOTHSTEP): Double {
        if (duration <= 0.0) return to
        val elapsed = wrapped(tick - sentTick).coerceAtLeast(0)
        return from + (to - from) * SmoothConfig.ease((elapsed / duration).coerceIn(0.0, 1.0), easing)
    }

    fun from() = from
    fun to() = to
    fun tick() = sentTick

    companion object {
        /**
         * The payload's tick field is an 8 bit counter modulo 240, so a difference is read in
         * [-120, 120) - the same wrap the shader does.
         */
        fun wrapped(delta: Int): Int = (((delta + 120) % 240 + 240) % 240) - 120

        fun quantize(value: Double, steps: Int): Double =
            (value.coerceIn(0.0, 1.0) * steps).roundToInt() / steps.toDouble()

        /**
         * World time in ticks, reduced the way the shader reads it back (`mod 240`).
         */
        fun tickOf(worldTime: Long): Int = (((worldTime % 240L) + 240L) % 240L).toInt()
    }
}

/**
 * Tracks how fast a value moves between two updates, so a payload can carry a rate the client keeps
 * extrapolating with. Without one, an element whose placeholder only changes once per tick steps visibly
 * at 20 Hz however fast the client renders.
 *
 * The rate is the plain difference over the ticks that passed. It is deliberately *not* filtered: the
 * client uses it over the one update it describes, so a lagging estimate would make it undershoot every
 * single update - which is a wobble, not less noise. `step` is called once per update and returns a
 * finished rate for the whole update as soon as the tick counter has moved.
 */
open class RateTracker(
    private var value: Double = 0.0,
    private var tick: Int = 0,
    private var rate: Double = 0.0
) {
    /** The first call only records the reference: there is no previous value to subtract from yet. */
    private var initialized = false

    /** The raw distance travelled since the last sampled update, before any wrapping. */
    protected open fun distance(previous: Double, value: Double) = value - previous

    fun step(value: Double, tick: Int): Double {
        val delta = SmoothValue.wrapped(tick - this.tick)
        if (initialized && delta > 0) {
            // Divided by the ticks that actually passed, not by one: an element with a slower update
            // rate (or a dropped update) still gets the rate of the motion the client has to cover.
            rate = distance(this.value, value) / delta
        }
        // The reference always follows the newest sample: a repeated tick (several frames of the same
        // update) must not be divided by zero, and the next delta is measured from the latest one.
        this.value = value
        this.tick = tick
        initialized = true
        return rate
    }
}

/**
 * The tween a scrolling element walks, in an unbounded domain.
 *
 * A ramp which fits inside the element's own update is left to finish, which is the shape a `clip-fill`
 * has; one which outlives it belongs to an input that changes every tick and is continued from where it
 * is, since waiting for it would quantize the motion. The value repeats with the content of the element,
 * so this keeps it in the branch it is already in rather than in the payload's 0..1 field.
 */
class ScrollTween(
    private var from: Double = 0.0,
    private var to: Double = 0.0,
    private var sentTick: Int = 0
) {
    /**
     * Moves towards [target]. [tick] is the world time in ticks reduced the way the payload is read back,
     * [duration] the ramp length in ticks, [quantize] the payload's step count, [continuous] whether the
     * input changes faster than a ramp can finish, and [easing] the curve both sides walk.
     */
    fun step(
        target: Double,
        tick: Int,
        duration: Double,
        quantize: Int,
        continuous: Boolean = false,
        easing: Int = SmoothConfig.SMOOTHSTEP,
        window: Double = WINDOW
    ): ScrollTween {
        val raw = align(target)
        val aligned = quantize(raw, quantize)
        // The payload's tick is what bounds the age a client can read back, so the ramp is cut to it.
        val ramp = duration.coerceIn(0.0, window)
        // A change smaller than one step of the grid is not a change: a placeholder sitting on a rounding
        // boundary would flip between two grid points and restart the ramp on every update.
        val moved = abs(raw - to) * quantize >= 1.0
        if (continuous) {
            if (moved && aligned != to) {
                from = quantize(curveAt(tick, ramp, easing), quantize)
                to = aligned
                sentTick = tick
            } else if (SmoothValue.wrapped(tick - sentTick) >= ramp) {
                // The input stopped. A ramp left in the payload would be replayed on every wrap of the four
                // bit tick, which shakes the element by whatever the last ramp covered.
                from = to
            }
            return this
        }
        // Cutting a running ramp short would make the client jump to whatever the old one had reached.
        val elapsed = SmoothValue.wrapped(tick - sentTick)
        if (from != to && ramp > 0.0 && elapsed >= 0 && elapsed < ramp) return this
        if (moved && aligned != to) {
            from = to
            to = aligned
            sentTick = tick
            return this
        }
        // Nothing left to walk: endpoints left in the payload replay on every wrap of the four bit tick.
        from = to
        return this
    }

    /**
     * The representative of [target] which is within half a period of the one this tween is on.
     */
    fun align(target: Double): Double {
        val delta = target - to
        return to + delta - delta.roundToInt()
    }

    /**
     * The value this tween is showing now, which is where a continuous input's next ramp starts from.
     */
    fun curveAt(
        tick: Int,
        duration: Double,
        easing: Int = SmoothConfig.SMOOTHSTEP,
        window: Double = WINDOW
    ): Double {
        val ramp = duration.coerceIn(0.0, window)
        if (ramp <= 0.0) return to
        // The same wrap the payload's four bit tick uses, so both sides read an age the same way.
        val elapsed = SmoothValue.wrapped(tick - sentTick).coerceAtLeast(0)
        val progress = (elapsed / ramp).coerceIn(0.0, 1.0)
        return from + (to - from) * SmoothConfig.ease(progress, easing)
    }

    /**
     * The endpoint this tween started from.
     */
    fun from() = from

    /**
     * The endpoint it walks to.
     */
    fun to() = to

    /**
     * The world time this tween was last retargeted at, which is what the client measures its age from.
     */
    fun tick() = sentTick

    /**
     * Puts a value on the grid the payload can carry, so both sides compute the same numbers.
     */
    private fun quantize(value: Double, quantize: Int): Double =
        (value * quantize).roundToInt() / quantize.toDouble()

    companion object {
        /**
         * The longest ramp a four bit tick can describe. A client reads the age as `mod(time - tick, 16)`
         * folded into [-8, 8), so half of the window is the furthest forward it can see: a ramp longer than
         * that never finishes and is snapped back to its start every time the counter wraps.
         */
        const val WINDOW = 8.0

        /**
         * The same bound for the eight bit tick (`bhPayload = 4`), whose counter runs to 240: its age is read
         * as `mod(time - tick, 240)` folded into [-120, 120), so half a window is what a ramp may use.
         */
        const val WIDE_WINDOW = 120.0

        /**
         * Wraps a value into the payload's 0..1 field.
         */
        fun wrap01(value: Double): Double = (value % 1.0 + 1.0) % 1.0
    }
}

/**
 * A rate in degrees per tick, taken the shortest way round: a needle passing 359 -> 1 degrees turned
 * one degree, not -358. Used by a rotation driven by a placeholder.
 */
class AngleRate(
    value: Double = 0.0,
    tick: Int = 0,
    rate: Double = 0.0
) : RateTracker(value, tick, rate) {
    override fun distance(previous: Double, value: Double): Double {
        var difference = (value - previous) % 360.0
        if (difference > 180.0) difference -= 360.0
        if (difference < -180.0) difference += 360.0
        return difference
    }
}
