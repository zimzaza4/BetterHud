package kr.toxicity.hud.util

import kotlin.math.roundToInt

/**
 * The bit layout of the "value + rate" payload (`bhPayload = 5`): an 11 bit value in red/green (with the
 * 5 bit tick in the low bits of that field) and an 8 bit rate in blue.
 *
 * It lives here, apart from Adventure, so the packing can be tested against the arithmetic the vertex
 * shader does when it reads the payload back. Every unit is passed in as a range plus the value which
 * lands in the middle of the field: [value] and [valueRange] share one unit, [rate] and [rateRange]
 * another, and neither side has to know whether the caller is talking about degrees, radians or pixels.
 *
 * The bug this guards is exactly a caller describing its rate in the wrong unit. The packed byte then
 * saturates and the client overshoots its whole range on every update, which reads as a violent shake
 * rather than as smoothing - so `rate` and `rateRange` have to be the same unit as each other, and that
 * unit has to be the one the shader multiplies by (`bhOmegaScale` for a rotation, `2 * bhValueScale` for
 * a scroll).
 */
object RatePayload {

    /**
     * The low five bits the payload's tick field carries, read straight off the world time.
     *
     * It must **not** be [kr.toxicity.hud.shader.SmoothValue.tickOf], which reduces by 240 for the other
     * payload's timestamp: the shader reads this field back through `bhAge(bits & 31, 32)`, i.e. modulo
     * 32, and 32 does not divide 240 (240 = 7 * 32 + 16). A tick reduced modulo 240 first therefore lands
     * 16 ticks off on every other 240-tick window, the client computes an age that cannot be right and
     * drops the rate - so the element steps at 20 Hz instead of being interpolated, half of the time, for
     * no visible reason.
     */
    fun tickOf(worldTime: Long): Int = (worldTime and 31L).toInt()

    /**
     * The 16 bits red and green carry: the value field in the top eleven, the tick in the low five.
     */
    fun bits(rgb: Int): Int = ((rgb shr 16 and 0xFF) shl 8) or (rgb shr 8 and 0xFF)

    /**
     * Where in the field a value belongs, as 0..1 - the mirror of what the shader reads back into
     * `bhNorm`. [valueCenter] is the value which lands exactly in the middle of the field.
     */
    fun normalized(value: Double, valueRange: Double, valueCenter: Double): Double {
        if (valueRange <= 0.0) return 0.5
        val raw = (value - valueCenter) / valueRange + 0.5
        return (raw % 1.0 + 1.0) % 1.0
    }

    /**
     * The 11 bit value field, from a value and the range which fills it. [valueCenter] defaults to the
     * middle of the range, which is the reading where the field spans `0 .. valueRange`; a scroll passes
     * `0.0` instead, because its field spans `-scale .. +scale` around a standstill of zero.
     */
    fun valueBits(value: Double, valueRange: Double, valueCenter: Double = valueRange / 2.0): Int =
        (normalized(value, valueRange, valueCenter) * 2047.0).roundToInt().coerceIn(0, 2047)

    /**
     * The 8 bit rate field, as a 0..1 fraction of [rateRange].
     *
     * A square law: the byte keeps `sign(rate) * sqrt(|rate| / rateRange)` and the shader undoes it with a
     * square. A linear field spends its resolution on the fast end, which leaves a slow turn decoded off by
     * a visible fraction of a tick's travel - a twitch which never settles.
     */
    fun rateFraction(rate: Double, rateRange: Double): Double {
        if (rateRange <= 0.0) return 0.0
        val magnitude = kotlin.math.sqrt(kotlin.math.abs(rate) / rateRange).coerceAtMost(1.0)
        return if (rate < 0.0) -magnitude else magnitude
    }

    /**
     * The 8 bit rate field: 128 is a standstill, and the field runs out at `+-rateRange`.
     */
    fun rateByte(rate: Double, rateRange: Double): Int =
        (rateFraction(rate, rateRange) * 128.0).roundToInt().plus(128).coerceIn(0, 255)

    /**
     * Packs the payload into a 24 bit colour, red in the high byte.
     */
    fun pack(
        value: Double,
        rate: Double,
        tick: Int,
        valueRange: Double,
        rateRange: Double,
        valueCenter: Double = valueRange / 2.0
    ): Int {
        val bits = (valueBits(value, valueRange, valueCenter) shl 5) or (tick and 31)
        return ((bits shr 8 and 0xFF) shl 16) or ((bits and 0xFF) shl 8) or rateByte(rate, rateRange)
    }

    /**
     * The tick the shader picks up from the low five bits of the value field.
     */
    fun decodedTick(rgb: Int): Int = bits(rgb) and 31

    /**
     * What the shader reads back out of the value field, in the caller's own unit - the mirror of
     * `float bhNorm = float(bhBits >> 5) / 2047.0` followed by whatever the case body does with it.
     */
    fun decodedValue(rgb: Int, valueRange: Double, valueCenter: Double = valueRange / 2.0): Double =
        ((bits(rgb) shr 5).toDouble() / 2047.0 - 0.5) * valueRange + valueCenter

    /**
     * What the shader reads back out of the rate field - the mirror of
     * `float q = (Color.b * 255.0 - 128.0) / 128.0; float bhRate = sign(q) * q * q;` times the range baked
     * into the case body.
     */
    fun decodedRate(rgb: Int, rateRange: Double): Double {
        val q = (rgb and 0xFF).toDouble().minus(128.0) / 128.0
        return Math.signum(q) * q * q * rateRange
    }

    /**
     * The two ten bit endpoints of one tween and the tick the update was made in (`bhPayload = 6`), the
     * layout a content scroll uses. Absolute endpoints cannot be overshot the way a rate can; the tick
     * phases the ramp on the update itself, which a pair of endpoints without one cannot do.
     */
    fun tweenBits(fraction: Double): Int = (fraction.coerceIn(0.0, 1.0) * 1023.0).roundToInt()

    /**
     * Packs both endpoints and the tick into one 24 bit colour, red in the high byte.
     */
    fun packTween(from: Double, to: Double, tick: Int): Int =
        (tweenBits(from) shl 14) or (tweenBits(to) shl 4) or (tick and 15)

    /**
     * The endpoint the ramp starts from, as a 0..1 fraction.
     */
    fun decodedTweenFrom(rgb: Int): Double = ((rgb shr 14) and 0x3FF).toDouble() / 1023.0

    /**
     * The endpoint the ramp walks to, as a 0..1 fraction.
     */
    fun decodedTweenTo(rgb: Int): Double = ((rgb shr 4) and 0x3FF).toDouble() / 1023.0

    /**
     * The tick the ramp was sent for, reduced modulo 16 the way the shader reads it back.
     */
    fun decodedTweenTick(rgb: Int): Int = rgb and 15

    /**
     * The world time reduced the way the ramp's four bit tick field is read back (`mod 16`), which is not
     * [tickOf]'s modulo 32 nor [kr.toxicity.hud.shader.SmoothValue.tickOf]'s modulo 240.
     */
    fun rampTickOf(worldTime: Long): Int = (worldTime and 15L).toInt()

    /**
     * The mixed layout (`bhPayload = 7`): an eight bit angle, then a six bit `from`, a six bit `to` and the
     * four bit tick they were sent for. The one layout carrying a turning element and a moving region at
     * once, which is what the value's sixty-four steps cost.
     */
    fun mixedBits(degree: Double, from: Double, to: Double, tick: Int): Int {
        val angle = ((degree / 360.0).mod(1.0) * 255.0).roundToInt() and 0xFF
        val start = (from.coerceIn(0.0, 1.0) * 63.0).roundToInt().coerceIn(0, 63)
        val end = (to.coerceIn(0.0, 1.0) * 63.0).roundToInt().coerceIn(0, 63)
        return (angle shl 16) or (start shl 10) or (end shl 4) or (tick and 15)
    }

    /** The angle, as the fraction of a turn the shader multiplies by two pi. */
    fun decodedMixedDegree(bits: Int): Double = (bits shr 16 and 0xFF).toDouble() / 255.0

    fun decodedMixedFrom(bits: Int): Double = (bits shr 10 and 0x3F).toDouble() / 63.0

    fun decodedMixedTo(bits: Int): Double = (bits shr 4 and 0x3F).toDouble() / 63.0

    fun decodedMixedTick(bits: Int): Int = bits and 15
}
