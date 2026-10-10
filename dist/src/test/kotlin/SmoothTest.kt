import kr.toxicity.hud.shader.AngleRate
import kr.toxicity.hud.shader.ScrollTween
import kr.toxicity.hud.shader.SmoothConfig
import kr.toxicity.hud.shader.SmoothValue
import kr.toxicity.hud.util.RatePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server half of the GPU side tweening: the rules which make the shader's payload land on the
 * values the server intended.
 */
class SmoothTest {

    /**
     * What a discrete rotation does under the one error the server cannot remove: the client's own tick
     * counter sits one to three ticks away from the server's. The server finishes its ramp and retargets
     * while the client is still walking that ramp, and the next payload starts from the endpoint the ramp
     * was heading for - so the client jumps the remainder it never drew.
     *
     * The point is the comparison: the same rotation walked continuously has no such edge, which is why a
     * fast turn belongs to that shape.
     */
    @Test
    fun testAShortDiscreteRampJumpsWhenTheClocksAreApart() {
        val offset = 3
        val perTick = 6.0 // degrees
        fun jumpFor(duration: Double, interval: Int, continuous: Boolean): Double {
            val tween = ScrollTween()
            var lastDrawn = 0.0
            var worst = 0.0
            var payload = Triple(0.0, 0.0, 0)
            for (tick in 0..120) {
                if (tick % interval == 0) {
                    val degree = (tick * perTick) % 360
                    tween.step(degree / 360.0, tick, duration, 1023, continuous)
                    payload = Triple(tween.from(), tween.to(), tween.tick())
                }
                // The client walks the payload on its own clock, which is `offset` ticks behind the server's.
                val (from, to, sentTick) = payload
                val age = ((tick - offset - sentTick) % 16 + 16) % 16
                val ramp = if (duration <= 0.0) 1.0 else (age / duration).coerceIn(0.0, 1.0)
                var delta = to - from
                if (delta > 0.5) delta -= 1.0 else if (delta < -0.5) delta += 1.0
                val drawn = (from + delta * ramp) * 360.0
                if (tick > 0) {
                    // An angle is periodic, so the distance between two frames is the short way round.
                    val raw = drawn - lastDrawn
                    val jump = ((raw % 360.0 + 540.0) % 360.0) - 180.0
                    worst = maxOf(worst, kotlin.math.abs(jump))
                }
                lastDrawn = drawn
            }
            return worst
        }
        val discrete = jumpFor(duration = 6.0, interval = 6, continuous = false)
        val continued = jumpFor(duration = 12.0, interval = 1, continuous = true)
        assertTrue(
            discrete > continued,
            "a short discrete ramp has to be the worse of the two (discrete $discrete, continued $continued)"
        )
        assertTrue(
            continued < perTick * 1.5,
            "a continued ramp has to stay within one tick of motion, was $continued"
        )
    }

    @Test
    fun testRetargetContinuesFromTheCurrentValue() {
        val state = SmoothValue().step(1.0, tick = 0, duration = 10.0, quantize = 255)
        val middle = state.curveAt(5, 10.0)
        assertTrue(middle > 0.4 && middle < 0.6, "smoothstep(0.5) has to be near the middle, was $middle")
        // A new target five ticks in has to start where the old curve is now, not where it started.
        val retargeted = state.step(0.0, tick = 5, duration = 10.0, quantize = 255)
        assertEquals(retargeted.curveAt(5, 10.0), retargeted.from(), 1.0 / 255)
    }

    @Test
    fun testTheMixedLayoutRoundTripsThroughTheShadersArithmetic() {
        // The one layout carrying a turning element and a moving region at once, so its grid is coarser than
        // the others: an eight bit angle over a sixty-four step value. Both sides have to agree on it, or the
        // boundary steps on its own every update.
        for (from in listOf(0.0, 0.25, 0.5, 1.0)) {
            for (to in listOf(0.0, 0.5, 0.75, 1.0)) {
                val bits = RatePayload.mixedBits(90.0, from, to, 11)
                assertEquals(90.0 / 360.0, RatePayload.decodedMixedDegree(bits), 1.0 / 255.0)
                assertEquals(from, RatePayload.decodedMixedFrom(bits), 1.0 / 63.0)
                assertEquals(to, RatePayload.decodedMixedTo(bits), 1.0 / 63.0)
                assertEquals(11, RatePayload.decodedMixedTick(bits))
            }
        }
        // A full turn is the same picture, so the angle has to wrap rather than saturate.
        assertEquals(0.0, RatePayload.decodedMixedDegree(RatePayload.mixedBits(360.0, 0.0, 0.0, 0)), 1e-9)
        assertEquals(0.75, RatePayload.decodedMixedDegree(RatePayload.mixedBits(-90.0, 0.0, 0.0, 0)), 1.0 / 255.0)
    }

    @Test
    fun testAFillTweenWalksTheEasingTheShaderRenders() {
        // `bhEase` is the curve the client draws. A retarget has to start from the point that curve is at,
        // so the server may not use a curve of its own - a filling bar with `easing: linear` was retargeted
        // along a smoothstep and got pulled back on every update.
        val linear = SmoothValue()
            .step(1.0, tick = 0, duration = 8.0, quantize = 255, easing = SmoothConfig.LINEAR)
        assertEquals(0.25, linear.curveAt(2, 8.0, SmoothConfig.LINEAR), 1.0 / 255)
        val smooth = SmoothValue().step(1.0, tick = 0, duration = 8.0, quantize = 255)
        assertEquals(0.15625, smooth.curveAt(2, 8.0), 0.01)
    }

    @Test
    fun testRepeatingTheSameTargetKeepsTheTransition() {
        val state = SmoothValue().step(1.0, tick = 0, duration = 10.0, quantize = 255)
        val before = state.from()
        state.step(1.0, tick = 5, duration = 10.0, quantize = 255)
        // The target did not change, so the transition must not be restarted (that would replay it).
        assertEquals(before, state.from(), 1.0 / 255)
    }

    @Test
    fun testFinishedTransitionCollapsesToTheTarget() {
        val state = SmoothValue().step(1.0, tick = 0, duration = 10.0, quantize = 255)
            .step(1.0, tick = 40, duration = 10.0, quantize = 255)
        // `from == to` is what keeps a wrapped tick counter from replaying a finished animation.
        assertEquals(state.to(), state.from(), 1.0 / 255)
    }

    @Test
    fun testTickWrapsLikeTheShader() {
        assertEquals(4, SmoothValue.wrapped(4))
        assertEquals(-4, SmoothValue.wrapped(236))
        assertEquals(0, SmoothValue.wrapped(240))
        assertEquals(0, SmoothValue.tickOf(240L))
        assertEquals(119, SmoothValue.tickOf(-121L))
    }

    @Test
    fun testQuantizationMatchesThePayloadGrid() {
        // Half a step rounds up, which is the grid the shader reads back.
        assertEquals(128.0 / 255.0, SmoothValue.quantize(0.5, 255), 1e-9)
        assertEquals(1.0, SmoothValue.quantize(2.0, 255), 1e-9)
        assertEquals(0.4, SmoothValue.quantize(0.4, 255), 1.0 / 255)
    }

    @Test
    fun testRotationRateTakesTheShortestWayRound() {
        val rate = AngleRate()
        rate.step(359.0, tick = 0)
        val delta = rate.step(1.0, tick = 1)
        // 359 -> 1 degrees is +2 degrees, not -358: the rate has to say so, and exactly - the client
        // extrapolates for one update, so a filtered estimate would undershoot every single update.
        assertEquals(2.0, delta, 0.001)
    }

    @Test
    fun testRotationRateIgnoresARepeatedTick() {
        val rate = AngleRate()
        rate.step(10.0, tick = 5)
        // No tick passed, so there is nothing to divide by: the rate must stay where it was.
        assertEquals(0.0, rate.step(40.0, tick = 5))
    }

    @Test
    fun testRotationRateDividesByTheTicksThatPassed() {
        val rate = AngleRate()
        rate.step(0.0, tick = 0)
        // An element which only updates every fourth tick still reports the rate of the whole update.
        assertEquals(3.0, rate.step(12.0, tick = 4), 0.001)
    }

    @Test
    fun testAngleRateTakesTheShortestArc() {
        // A needle passing 359 -> 1 degrees turned two degrees, not -358: the rate has to be folded into
        // the circle, unlike the plain difference a value without a period uses.
        val rate = AngleRate()
        rate.step(0.0, tick = 0)
        assertEquals(40.0, rate.step(40.0, tick = 1), 0.001)
        rate.step(359.0, tick = 2)
        assertEquals(2.0, rate.step(1.0, tick = 3), 0.001)
    }

    @Test
    fun testTheRampRoundTripsOnItsOwnGrid() {
        // The value the client decodes comes straight back as the next update's `from`, so a round-off here
        // would open a seam at every join - the twitch this layout exists to remove.
        val span = 720.0
        var previous = RatePayload.tweenBits(RatePayload.normalized(-360.0, span, 0.0))
        for (pixels in listOf(-359.0, -180.0, 0.0, 42.5, 359.9)) {
            val to = RatePayload.tweenBits(RatePayload.normalized(pixels, span, 0.0))
            val packed = RatePayload.packTween(previous / 1023.0, to / 1023.0, 7)
            assertEquals(
                previous, RatePayload.tweenBits(RatePayload.decodedTweenFrom(packed)),
                "the client's `from` must decode to the exact grid point the server packed"
            )
            assertEquals(
                to, RatePayload.tweenBits(RatePayload.decodedTweenTo(packed)),
                "the client's `to` must decode to the exact grid point the server packed"
            )
            assertEquals(7, RatePayload.decodedTweenTick(packed), "the tick field has to survive the packing")
            assertEquals(
                pixels, (RatePayload.decodedTweenTo(packed) * 2.0 - 1.0) * (span / 2.0), span / 1023.0,
                "the decoded endpoint has to land within one grid step of the pixel it stands for"
            )
            previous = to
        }
    }

    @Test
    fun testTheRampTickIsReducedTheWayTheShaderReadsIt() {
        // The shader reads it back through `bhAge(bits & 15, 16)`, so the field is the world tick modulo 16.
        for (worldTime in 0L..3000L) {
            assertEquals((worldTime % 16L).toInt(), RatePayload.rampTickOf(worldTime), "world tick $worldTime")
        }
    }

    @Test
    fun testRatePayloadRoundTripsThroughTheShadersArithmetic() {
        // A rotation: degrees over 360 (the field spans 0 .. 360), radians per tick over the omega scale.
        val omega = Math.toRadians(30.0)
        val packed = RatePayload.pack(120.0, Math.toRadians(6.0), 17, 360.0, omega)
        assertEquals(120.0, RatePayload.decodedValue(packed, 360.0), 0.1)
        assertEquals(Math.toRadians(6.0), RatePayload.decodedRate(packed, omega), 0.01)
        assertEquals(17, RatePayload.decodedTick(packed))
        // A scroll: a pixel offset over twice the scale centred on a standstill of zero, and a pixel rate
        // over that same span. This is the unit which has to line up with `bhRate * bhTicks * 2 * scale`.
        val scroll = RatePayload.pack(-120.0, 36.0, 3, 720.0, 720.0, 0.0)
        assertEquals(-120.0, RatePayload.decodedValue(scroll, 720.0, 0.0), 0.4)
        assertEquals(36.0, RatePayload.decodedRate(scroll, 720.0), 3.0)
    }

    @Test
    fun testAFreshRatePayloadNeverLooksSixteenTicksOld() {
        // The shader reads the payload's timestamp back through `bhAge(bits & 31, 32)`, so the field has
        // to be the world tick modulo 32. Packing `SmoothValue.tickOf` (modulo 240) instead puts it 16
        // ticks off on every other 240-tick window - 32 does not divide 240 - and the client then drops a
        // rate whose age cannot be right, which is a scrolling compass stepping half of the time.
        for (worldTime in 0L..3000L) {
            val packed = RatePayload.pack(0.0, 0.0, RatePayload.tickOf(worldTime), 360.0, 1.0)
            val age = shaderAge(RatePayload.decodedTick(packed), worldTime)
            // A payload written for this very tick has to read back as "no time has passed yet".
            assertEquals(0.0, age, 1e-9, "world tick $worldTime")
        }
    }

    /** The mirror of `bhAge(int tick, float period)` in `text.vsh`. */
    private fun shaderAge(tick: Int, worldTime: Long): Double {
        // GameTime * 24000 is the world time in ticks within the current day.
        val gameTime = (worldTime % 24000L).toDouble()
        return (gameTime - tick + 16.0).mod(32.0) - 16.0
    }

    @Test
    fun testRatePayloadSaturatesInsteadOfWrapping() {
        // A rate beyond what the field can express must clamp, not roll over into the other direction.
        val omega = Math.toRadians(30.0)
        val packed = RatePayload.pack(0.0, Math.toRadians(600.0), 0, 360.0, omega)
        assertTrue(RatePayload.decodedRate(packed, omega) > 0, "a fast turn must not come back negative")
    }

    @Test
    fun testARestingScrollCollapsesItsRamp() {
        // Once nothing moves, the payload must stop carrying a ramp: the four bit tick counter wraps, and a
        // ramp left in the payload replays on every wrap - shaking the element by whatever the last ramp
        // covered, which is exactly how fast the input was going before it stopped.
        val tween = ScrollTween()
        val target = RatePayload.normalized(42.0, 720.0, 0.0)
        tween.step(target, 0, 4.0, 1023)
        for (time in 1..40) tween.step(target, time, 4.0, 1023)
        assertEquals(tween.to(), tween.from(), "a resting scroll has to collapse onto its endpoint")
    }

    @Test
    fun testAScrollIgnoresAChangeSmallerThanItsGrid() {
        // A placeholder which sits on a rounding boundary flips between the two grid points next to it on
        // almost every update. Each flip would restart the ramp, shaking the element inside one step.
        val tween = ScrollTween().step(0.25, 0, 4.0, 1023)
        val to = tween.to()
        val tick = tween.tick()
        tween.step(tween.to() + 0.5 / 1023.0, 10, 4.0, 1023)
        assertEquals(to, tween.to(), "a change smaller than one grid step is not a change")
        assertEquals(tick, tween.tick(), "and it must not restart the ramp")
    }

    @Test
    fun testAScrollStartsFromTheEndpointItLandedOn() {
        // The client is standing on the previous endpoint when the next update arrives (a ramp is one
        // update long), so that is where the next one has to start: the server must not hand it a value
        // derived from its own clock, which the client's own tick offset would turn into a jump.
        val tween = ScrollTween()
        tween.step(0.25, 0, 4.0, 1023)
        val landed = tween.to()
        tween.step(0.75, 4, 4.0, 1023)
        assertEquals(landed, tween.from(), "a new ramp starts where the previous one ended")
    }

    @Test
    fun testAWrappingScrollAlignsToTheNearestPeriod() {
        // The value of a compass jumps a whole period when the yaw crosses +-180, and a period away is the
        // same picture: aligning such a target must stay on the branch in use rather than travel the circle.
        val tween = ScrollTween().step(0.999, 0, 1.0, 1023)
        assertEquals(tween.to(), tween.align(tween.to() - 1.0), 0.01, "a target a period back must align")
        assertEquals(tween.to(), tween.align(tween.to() + 1.0), 0.01, "a target a period on must align")
    }

    @Test
    fun testAScrollRampIsCappedToThePayloadsTimestamp() {
        // The payload's tick is four bits, so a ramp longer than that is one the client cannot measure:
        // both sides have to reduce it to the window, or they walk different curves.
        val long = ScrollTween().step(1.0, 0, 40.0, 1023, continuous = true)
        val capped = ScrollTween().step(1.0, 0, ScrollTween.WINDOW, 1023, continuous = true)
        assertEquals(
            capped.curveAt(8, ScrollTween.WINDOW), long.curveAt(8, 40.0), 0.001,
            "a ramp longer than the timestamp has to walk the window's curve"
        )
    }

    @Test
    fun testAContinuousScrollCollapsesOnceItsInputStops() {
        // A continued ramp left in the payload is replayed on every wrap of the four bit tick, which is the
        // shake a compass shows when it comes to rest.
        val tween = ScrollTween()
        val target = RatePayload.normalized(42.0, 720.0, 0.0)
        tween.step(target, 0, 4.0, 1023, continuous = true)
        for (time in 1..40) tween.step(target, time, 4.0, 1023, continuous = true)
        assertEquals(tween.to(), tween.from(), "a stopped continuous scroll has to collapse onto its endpoint")
    }

    @Test
    fun testAScrollWalksTheSameEasingCurveAsTheShader() {
        // `bhEase` is what the client renders with; the server retargets a continuous input from the same
        // curve, so the two tables have to agree.
        assertEquals(0.5, SmoothConfig.ease(0.5, SmoothConfig.LINEAR), 1e-9)
        assertEquals(0.75, SmoothConfig.ease(0.5, SmoothConfig.EASE_OUT), 1e-9)
        assertEquals(0.5, SmoothConfig.ease(0.5, SmoothConfig.SMOOTHSTEP), 1e-9)
        assertEquals(0.0, SmoothConfig.ease(0.0, SmoothConfig.SMOOTHSTEP), 1e-9)
        assertEquals(1.0, SmoothConfig.ease(1.0, SmoothConfig.SMOOTHSTEP), 1e-9)
    }
}
