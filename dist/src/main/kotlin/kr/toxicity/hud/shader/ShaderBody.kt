package kr.toxicity.hud.shader

/**
 * The body of one element's case: what the `#CreateLayout` dispatch runs for it.
 *
 * Extracted from the shader manager so that "two elements share a case" can be checked against
 * "two elements generate the same lines" in a test - the identity of [HudShader], which is the key of
 * the tree map that assigns the case ids, depends on it.
 */
object ShaderBody {

    fun lines(shader: HudShader): List<String> {
        val body = arrayListOf<String>()
        // The element's own position (animation offset + element pixel offset), emitted unconditionally.
        // NOTE: nothing reads bhAnchorX/Y yet - the shader derives the pivot from the glyph center (text.vsh),
        // which is why an element position does not have to split a case (see RenderScale.compareTo).
        body.add("    bhAnchorX = ${shader.renderScale.relativeOffset.x.toDouble()};")
        body.add("    bhAnchorY = ${shader.renderScale.relativeOffset.y.toDouble()};")
        if (shader.property > 0) body.add("    property = ${shader.property};")
        if (shader.opacity < 1.0) body.add("    opacity = ${shader.opacity.toFloat()};")
        val static = shader.renderScale.scale.staticScale
        fun applyScale(offset: Int, scale: Double, pos: String) {
            if (scale != 1.0 || static) {
                val scaleFloat = scale.toFloat()
                body.add("    pos.$pos = (pos.$pos - (${offset})) * ${if (static) "$scaleFloat * uiScreen.$pos" else scaleFloat} + (${offset});")
            }
        }
        applyScale(shader.renderScale.relativeOffset.x, shader.renderScale.scale.x, "x")
        applyScale(shader.renderScale.relativeOffset.y, shader.renderScale.scale.y, "y")
        if (shader.gui.x != 0.0) body.add("    xGui = ui.x * ${shader.gui.x.toFloat()} / 100.0;")
        if (shader.gui.y != 0.0) body.add("    yGui = ui.y * ${shader.gui.y.toFloat()} / 100.0;")
        if (shader.layer != 0) body.add("    layer = ${shader.layer};")
        if (shader.outline != 0) body.add("    outline = true;")
        shader.smooth?.let {
            // A scrolling element's payload carries a four bit tick, so a ramp longer than its window is one
            // its client cannot measure. The other layouts carry a wider counter and keep their own length.
            val duration = if (shader.scroll != null) it.duration.coerceIn(0.0, ScrollTween.WINDOW)
            else it.duration
            body.add("    bhSmoothDur = ${duration.toFloat()};")
            body.add("    bhSmoothEase = ${it.easing}.0;")
            if (it.omegaScale != 0.0) body.add("    bhOmegaScale = ${it.omegaScale.toFloat()};")
        }
        shader.clip?.let {
            body.add("    bhFillOn = true;")
            body.add("    bhFillMin = vec2(${it.x.toFloat()}, ${it.y.toFloat()});")
            body.add("    bhFillMax = vec2(${(it.x + it.width).toFloat()}, ${(it.y + it.height).toFloat()});")
            if (it.radius != 0.0) body.add("    bhFillRadius = ${it.radius.toFloat()};")
            if (it.soft != 0.0) body.add("    bhFillSoft = ${it.soft.toFloat()};")
            body.add("    bhFillAxis = ${it.axis};")
            if (it.invert) body.add("    bhFillInvert = true;")
            if (!it.followsContent) body.add("    bhFillFollow = false;")
            // The region is measured from the element's top-left, while the shader's pivot is its centre.
            body.add("    bhElemHalf = vec2(${it.elementHalfX.toFloat()}, ${it.elementHalfY.toFloat()});")
        }
        shader.scroll?.let {
            // The payload drives a position instead of a boundary: 1 = x, 2 = y (see bhValueUse).
            body.add("    bhValueUse = ${it.axis + 1};")
            body.add("    bhValueScale = ${it.scale.toFloat()};")
        }
        val payload = PayloadKind.of(
            shader.rotationDynamic,
            shader.positionDynamic,
            shader.rotationSmooth,
            shader.valueSmooth,
            shader.scrollSmooth || (shader.rotationTween && !shader.rotationWide),
            shader.rotationWide
        )
        if (shader.rotationWide) {
            // The eight bit payload drives the angle itself instead of a boundary or an offset.
            body.add("    bhValueRot = true;")
        }
        if (shader.transforms) {
            body.add("    bhRotOn = true;")
            body.add("    bhRot = ${Math.toRadians(shader.rotationDegree).toFloat()};")
            body.add("    bhRotHalf = vec2(${shader.rotationHalfX.toFloat()}, ${shader.rotationHalfY.toFloat()});")
            // A glyph of a cut image only covers a piece of the element: move the pivot from the piece's
            // own center to the element's one, so every piece turns around the same point.
            // Guarded, so a hand-edited text.vsh which predates the anchor still compiles.
            if (shader.rotationAnchorX != 0.0 || shader.rotationAnchorY != 0.0) {
                body.add("#ifdef BH_ROT_ANCHOR")
                body.add("    bhRotAnchor = vec2(${shader.rotationAnchorX.toFloat()}, ${shader.rotationAnchorY.toFloat()});")
                body.add("#endif")
            }
            body.add("    bhPayload = $payload;")
            // Rotate the payload offset together with the element (see HudLayout.positionInRotatedSpace).
            body.add("    bhRotateOffset = ${shader.positionInRotatedSpace};")
            if (shader.clipOuter > 0.0) {
                body.add("    bhClipIn = ${shader.clipInner.toFloat()};")
                body.add("    bhClipOut = ${shader.clipOuter.toFloat()};")
                // Only emitted when a clip-origin was given explicitly; every other element keeps
                // byte-for-byte the behaviour it had before.
                if (shader.hasClipOrigin) {
                    body.add("    bhClipHasOrigin = true;")
                    body.add("    bhClipOrigin = vec2(${shader.clipOriginX.toFloat()}, ${shader.clipOriginY.toFloat()});")
                }
            }
        }
        return body
    }
}
