package kr.toxicity.hud.renderer

import kr.toxicity.hud.api.component.PixelComponent
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.image.ImageComponent
import kr.toxicity.hud.layout.ImageLayout
import kr.toxicity.hud.manager.PlaceholderManagerImpl
import kr.toxicity.hud.manager.PlayerManagerImpl
import kr.toxicity.hud.shader.AngleRate
import kr.toxicity.hud.shader.ClipRegion
import kr.toxicity.hud.shader.Rotation
import kr.toxicity.hud.shader.ScrollTween
import kr.toxicity.hud.shader.SmoothValue
import kr.toxicity.hud.util.*
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.roundToInt

class ImageRenderer(
    layout: ImageLayout,
    component: ImageComponent
) : ImageLayout by layout, HudRenderer {
    private val followHudPlayer = follow?.let {
        PlaceholderManagerImpl.find(it, this).assertString("This placeholder is not a string: $it")
    }
    private val component = component.apply(outline, color)

    override fun render(event: UpdateEvent): TickProvider<HudPlayer, PixelComponent> {
        val cond = conditions build event
        val listen = component.listener(event)
        val follow = followHudPlayer?.build(event)

        val stackGetter = stack?.build(event)
        val maxStackGetter = maxStack?.build(event)

        val mapper = component mapper event
        val colorApply = colorOverrides(event)
        val rotationGetter = (rotation as? Rotation.Dynamic)?.degree?.build(event)
        val positionXGetter = positionX?.build(event)
        val positionYGetter = positionY?.build(event)
        // `clip-fill.value` drives the boundary of the custom clip region, `smooth` lets the GPU walk it.
        val fillGetter = clipFillValue?.build(event)
        // `clip` is a delegated property, which cannot be smart cast, so it is read into a local first.
        val region = clip
        val fillSmooth = smooth?.takeIf { region != null && region.axis != ClipRegion.NONE && fillGetter != null }
        val rotationSmooth = smooth?.takeIf { it.omegaScale > 0.0 && rotationGetter != null }
        // An angle with a `smooth` which did not ask for a rate takes two endpoints instead: it is a scalar
        // with its own period, and an endpoint cannot be overshot the way an extrapolated rate can.
        val rotationTween = smooth?.takeIf { it.omegaScale <= 0.0 && rotationGetter != null }
        val rotationWide = smooth?.takeIf {
            it.omegaScale <= 0.0 && rotationGetter != null && it.duration > ScrollTween.WINDOW
        }
        val fillStates = if (fillSmooth != null) WeakHashMap<HudPlayer, SmoothValue>() else null
        val rotationStates = if (rotationSmooth != null) WeakHashMap<HudPlayer, AngleRate>() else null
        val rotationTweens = if (rotationTween != null) WeakHashMap<HudPlayer, ScrollTween>() else null
        val rotationWides = if (rotationWide != null) WeakHashMap<HudPlayer, ScrollTween>() else null
        // A content scroll shares the precise, rate carrying payload of a tweened rotation (an 11 bit
        // value and an 8 bit rate), which is what a long strip needs: range and continuity.
        val scrollGetter = scrollValue?.build(event)
        val scrolling = scroll
        val scrollSmooth = smooth?.takeIf { scrolling != null && scrollGetter != null }
        // The tween a scrolling element is walking right now: its `from` is re-based on every update and
        // its domain is unbounded (a wrapping placeholder counts whole periods), so the state is kept per
        // player and per element.
        val scrollStates = if (scrollSmooth != null) WeakHashMap<HudPlayer, ScrollTween>() else null

        return tickProvide(tick) build@ { player, frame ->
            val selected = mapper(player)

            val stackFrame = (stackGetter?.value(player) as? Number)?.toDouble() ?: 0.0
            val maxStackFrame = (maxStackGetter?.value(player) as? Number)?.toInt()?.coerceAtLeast(1) ?: ceil(stackFrame).toInt()

            var target = player
            follow?.let {
                PlayerManagerImpl.getHudPlayer(it.value(player).toString())?.let { p ->
                    target = p
                } ?: run {
                    if (cancelIfFollowerNotExists) return@build EMPTY_PIXEL_COMPONENT
                }
            }
            if (cond(target)) {
                if (maxStackFrame > 1) {
                    if (stackFrame <= 0.0) return@build EMPTY_PIXEL_COMPONENT
                    var empty = EMPTY_PIXEL_COMPONENT
                    val range = 0..<maxStackFrame
                    for (i in if (reversed) range.reversed() else range) {
                        val frame = ((stackFrame - i - 0.1) * selected.images.size)
                            .roundToInt()
                            .coerceAtLeast(0)
                            .coerceAtMost(selected.images.lastIndex)
                        empty = empty.append(space, selected.images[frame])
                    }
                    empty.applyColor(colorApply(target))
                } else {
                    // payload and colour are applied to a copy now, so the returned value
                    // has to be used: writing them into the component in place leaked one
                    // player's rotation/offset into every other player sharing that cache.
                    val base = component.type.getComponent(listen, frame, selected, target)
                    // The world clock the shader compares the payload's tick against; a platform without
                    // one reports 0 and the element keeps its per-update step.
                    val worldTick = player.gameTime()
                    val fallbackTick = if (worldTick > 0L) worldTick else player.tick
                    // Payload 4's timestamp is an eight bit counter the shader reduces by 240, payload 5's is
                    // a five bit one it reduces by 32 - and 240 is not a multiple of 32.
                    val time240 = SmoothValue.tickOf(fallbackTick)
                    val rateTick = RatePayload.tickOf(fallbackTick)
                    when {
                        scrollSmooth != null && scrollGetter != null && scrolling != null -> {
                            val pixels = (scrollGetter.value(target) as? Number)?.toDouble() ?: 0.0
                            // The shape follows the element's own update: a ramp it can contain is left to
                            // finish (an input sampled every few ticks), one which outlives it is continued.
                            val state = scrollStates!!.getOrPut(target) { ScrollTween() }.step(
                                RatePayload.normalized(pixels, scrolling.scale * 2.0, 0.0),
                                time240,
                                scrollSmooth.duration,
                                1023,
                                scrollSmooth.duration > tick,
                                scrollSmooth.easing
                            )
                            base.applyTweenPayload(
                                ScrollTween.wrap01(state.from()),
                                ScrollTween.wrap01(state.to()),
                                state.tick()
                            )
                        }
                        fillSmooth != null && fillGetter != null -> {
                            val raw = (fillGetter.value(target) as? Number)?.toDouble() ?: 0.0
                            // Percentages are what placeholders usually carry, so anything above 1 is read as one.
                            val value = if (raw > 1.0) raw / 100.0 else raw
                            val state = fillStates!!.getOrPut(target) { SmoothValue() }
                                .step(value, time240, fillSmooth.duration, 255)
                            base.applySmoothValuePayload(state.from(), state.to(), state.tick())
                        }
                        rotationWide != null && rotationGetter != null -> {
                            val degree = (rotationGetter.value(target) as? Number)?.toDouble() ?: 0.0
                            // A turn too fast for the four bit tick's ramp: the eight bit payload's tick
                            // counts to 240, so the ramp can cover the part of the step a continued one
                            // leaves behind. The angle is still normalized and walked the short way round.
                            val state = rotationWides!!.getOrPut(target) { ScrollTween() }.step(
                                degree / 360.0,
                                time240,
                                rotationWide.duration,
                                255,
                                rotationWide.duration > tick,
                                rotationWide.easing,
                                ScrollTween.WIDE_WINDOW
                            )
                            base.applySmoothValuePayload(
                                ScrollTween.wrap01(state.from()),
                                ScrollTween.wrap01(state.to()),
                                state.tick() and 255
                            )
                        }
                        rotationTween != null && rotationGetter != null -> {
                            val degree = (rotationGetter.value(target) as? Number)?.toDouble() ?: 0.0
                            // The angle is a scalar with its own 360 degree period, so it is normalized and
                            // the tween takes the short way round a whole turn, exactly as a scroll does.
                            val state = rotationTweens!!.getOrPut(target) { ScrollTween() }.step(
                                degree / 360.0,
                                time240,
                                rotationTween.duration,
                                1023,
                                rotationTween.duration > tick,
                                rotationTween.easing
                            )
                            base.applyTweenPayload(
                                ScrollTween.wrap01(state.from()),
                                ScrollTween.wrap01(state.to()),
                                state.tick()
                            )
                        }
                        rotationSmooth != null && rotationGetter != null -> {
                            val degree = (rotationGetter.value(target) as? Number)?.toDouble() ?: 0.0
                            // The tracker works in degrees; the payload's rate field is scaled in radians
                            // per tick (`bhOmegaScale`), so the rate is converted before it is packed.
                            val rate = rotationStates!!.getOrPut(target) { AngleRate() }.step(degree, time240)
                            base.applyRatePayload(
                                degree, Math.toRadians(rate), rateTick, 360.0, rotationSmooth.omegaScale
                            )
                        }
                        rotationGetter != null || positionXGetter != null -> base.applyTransformPayload(
                            rotationGetter != null,
                            positionXGetter != null,
                            (rotationGetter?.value(target) as? Number)?.toDouble() ?: 0.0,
                            (positionXGetter?.value(target) as? Number)?.toInt() ?: 0,
                            (positionYGetter?.value(target) as? Number)?.toInt() ?: 0
                        )
                        // applyColor is copy-based now: use its result, an in-place call would be a no-op.
                        else -> base applyColor colorApply(target)
                    }
                }
            } else {
                if (clearListener) listen.clear(player)
                EMPTY_PIXEL_COMPONENT
            }
        }
    }

    fun max() = component.max
}