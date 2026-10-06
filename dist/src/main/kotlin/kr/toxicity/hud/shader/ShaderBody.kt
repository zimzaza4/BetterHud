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
        val payload = PayloadKind.of(shader.rotationDynamic, shader.positionDynamic)
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
