package kr.toxicity.hud.shader

import kr.toxicity.hud.location.GuiLocation

/**
 * The key which decides whether two elements share one case of `text.vsh`.
 *
 * Identity follows the generated code (see [ShaderBody]), not the field list: a field which emits no
 * line must not split a case, or a config with many differently sized elements inflates into one case
 * per element (each case is a compiled switch branch, and there are only 255 element ids at all).
 */
data class HudShader(
    val gui: GuiLocation,
    val renderScale: RenderScale,
    val layer: Int,
    val outline: Int,
    val opacity: Double,
    val property: Int,
    val rotationDegree: Double = 0.0,
    val rotationDynamic: Boolean = false,
    val positionDynamic: Boolean = false,
    val rotationHalfX: Double = 0.0,
    val rotationHalfY: Double = 0.0,
    // The offset in `position` is expressed in the element's rotated frame (rotated together with
    // `rotation`). Formerly named `position-in-map-space`: BetterHud has no notion of a "map", that
    // name leaked in from a minimap use case.
    val positionInRotatedSpace: Boolean = false,
    val clipInner: Double = 0.0,
    val clipOuter: Double = 0.0,
    // Explicit clip circle center (optional). Ignored unless hasClipOrigin is true, in which case the
    // shader uses it instead of the element's own center (bhPivot).
    val clipOriginX: Double = 0.0,
    val clipOriginY: Double = 0.0,
    val hasClipOrigin: Boolean = false,
    // Offset from the center of this glyph to the center the element turns (and clips) around. Zero for
    // an element drawn as one glyph; every tile of a cut image carries its own, or the pieces would
    // rotate around different points.
    val rotationAnchorX: Double = 0.0,
    val rotationAnchorY: Double = 0.0,
    // The custom clip region (`clip-rect`), the tween settings (`smooth`) and a placeholder driven
    // content scroll (`scroll`). Null unless the config asks for them, so an element which predates
    // them generates the very same case body.
    val clip: ClipRegion? = null,
    val smooth: SmoothConfig? = null,
    val scroll: ScrollConfig? = null,
) : Comparable<HudShader> {

    /**
     * Whether the payload carries a tweened value: a filling region walks from the value it had to
     * the new one while the client keeps drawing.
     */
    val valueSmooth: Boolean
        get() = smooth != null && clip != null && clip.axis != ClipRegion.NONE

    /**
     * Whether the payload carries a tweened angle together with its rate.
     */
    val rotationSmooth: Boolean
        get() = smooth != null && smooth.omegaScale > 0.0 && rotationDynamic

    /**
     * Whether the payload carries a tweened angle as two known endpoints instead of a rate, so a turn which
     * comes to a stop lands on its endpoint rather than overshooting the one it was heading for.
     */
    val rotationTween: Boolean
        get() = smooth != null && smooth.omegaScale <= 0.0 && rotationDynamic

    /**
     * Whether that tween is long enough to need the eight bit payload. Its tick counts to 240 where the four
     * bit one counts to 16, and a continued ramp is off by the part of its step the ramp has not covered - so
     * a longer ramp is the only way a fast turn stops showing that error.
     */
    val rotationWide: Boolean
        get() = rotationTween && smooth!!.duration > ScrollTween.WINDOW

    /**
     * Whether the payload carries a scrolling content offset and its rate.
     */
    val scrollSmooth: Boolean
        get() = smooth != null && scroll != null

    /**
     * Whether the case body emits the transform block: everything below [property] in [ShaderBody].
     * An element which neither turns, nor takes a payload, nor clips must not be split by those fields.
     */
    val transforms: Boolean
        get() = rotationDynamic || positionDynamic || rotationDegree != 0.0 || clipOuter > 0.0 ||
            clip != null || scroll != null

    /**
     * Whether the vertex shader turns (or clips) this element around its own centre.
     * Only then does a tile of a cut image have to be told where that centre is.
     *
     * A clip region needs this just as much as a rotation does: a long strip which is cut into tiles
     * would otherwise measure the region from each tile's own centre, so every tile would show its own
     * copy of the window instead of one window across the whole picture.
     */
    val pivotsAroundElementCenter: Boolean
        get() = rotationDynamic || rotationDegree != 0.0 || clipOuter > 0.0 || clip != null || scroll != null

    override fun compareTo(other: HudShader): Int {
        var result = gui.compareTo(other.gui)
        if (result != 0) return result
        result = renderScale.compareTo(other.renderScale)
        if (result != 0) return result
        result = layer.compareTo(other.layer)
        if (result != 0) return result
        // The body only writes `outline = true`, so the value itself is irrelevant.
        result = (outline != 0).compareTo(other.outline != 0)
        if (result != 0) return result
        result = opacity.compareTo(other.opacity)
        if (result != 0) return result
        result = property.compareTo(other.property)
        if (result != 0) return result
        // A custom region or tween settings reach the body for every element which sets them, so they
        // are compared before the early return below (which only skips the transform block).
        result = compareValues(clip, other.clip)
        if (result != 0) return result
        result = compareValues(smooth, other.smooth)
        if (result != 0) return result
        result = compareValues(scroll, other.scroll)
        if (result != 0) return result
        if (!transforms && !other.transforms) return 0
        result = rotationDynamic.compareTo(other.rotationDynamic)
        if (result != 0) return result
        result = positionDynamic.compareTo(other.positionDynamic)
        if (result != 0) return result
        result = rotationDegree.compareTo(other.rotationDegree)
        if (result != 0) return result
        result = rotationHalfX.compareTo(other.rotationHalfX)
        if (result != 0) return result
        result = rotationHalfY.compareTo(other.rotationHalfY)
        if (result != 0) return result
        result = positionInRotatedSpace.compareTo(other.positionInRotatedSpace)
        if (result != 0) return result
        if (clipOuter != other.clipOuter) return clipOuter.compareTo(other.clipOuter)
        if (clipOuter > 0.0) {
            result = clipInner.compareTo(other.clipInner)
            if (result != 0) return result
            result = hasClipOrigin.compareTo(other.hasClipOrigin)
            if (result != 0) return result
            // The origin is only written when it was given; otherwise it is the element's own center.
            if (hasClipOrigin) {
                result = clipOriginX.compareTo(other.clipOriginX)
                if (result != 0) return result
                result = clipOriginY.compareTo(other.clipOriginY)
                if (result != 0) return result
            }
        }
        result = rotationAnchorX.compareTo(other.rotationAnchorX)
        if (result != 0) return result
        result = rotationAnchorY.compareTo(other.rotationAnchorY)
        if (result != 0) return result
        return 0
    }

    /**
     * Identity is the same question as [compareTo]: this class is the key of the tree map which assigns
     * the case ids, so "compareTo == 0" and "generates the same case body" have to mean the same thing.
     */
    override fun equals(other: Any?): Boolean = other is HudShader && compareTo(other) == 0

    override fun hashCode(): Int {
        var result = gui.hashCode()
        result = 31 * result + renderScale.hashCode()
        result = 31 * result + layer
        result = 31 * result + (outline != 0).hashCode()
        result = 31 * result + opacity.hashCode()
        result = 31 * result + property
        result = 31 * result + (clip?.hashCode() ?: 0)
        result = 31 * result + (smooth?.hashCode() ?: 0)
        result = 31 * result + (scroll?.hashCode() ?: 0)
        if (!transforms) return result
        result = 31 * result + rotationDynamic.hashCode()
        result = 31 * result + positionDynamic.hashCode()
        result = 31 * result + rotationDegree.hashCode()
        result = 31 * result + rotationHalfX.hashCode()
        result = 31 * result + rotationHalfY.hashCode()
        result = 31 * result + positionInRotatedSpace.hashCode()
        if (clipOuter > 0.0) {
            result = 31 * result + clipInner.hashCode()
            result = 31 * result + clipOuter.hashCode()
            result = 31 * result + hasClipOrigin.hashCode()
            if (hasClipOrigin) {
                result = 31 * result + clipOriginX.hashCode()
                result = 31 * result + clipOriginY.hashCode()
            }
        }
        result = 31 * result + rotationAnchorX.hashCode()
        result = 31 * result + rotationAnchorY.hashCode()
        return result
    }

    fun toBackground(otherOpacity: Double) = HudShader(
        gui,
        renderScale,
        layer,
        0,
        opacity * otherOpacity,
        property
    )

    fun toFancyHead() = HudShader(
        gui,
        renderScale * 1.125,
        layer + 1,
        0x8F shl 24,
        opacity,
        property
    )
}
