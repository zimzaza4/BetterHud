package kr.toxicity.hud.renderer

import kr.toxicity.hud.shader.AngleRate
import kr.toxicity.hud.shader.ScrollTween
import kr.toxicity.hud.shader.SmoothValue

/**
 * One player's tween state. The fields are built on first use - an element walks exactly one payload
 * arm, so a holder normally carries a single state.
 */
class TweenState {
    val fill by lazy { SmoothValue() }
    val rotation by lazy { AngleRate() }
    val rotationTween by lazy { ScrollTween() }
    val rotationWide by lazy { ScrollTween() }
    val scroll by lazy { ScrollTween() }
}
