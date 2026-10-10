package kr.toxicity.hud.layout

import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.element.HudElement
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.placeholder.ConditionSource
import kr.toxicity.hud.placeholder.PlaceholderBuilder
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.shader.ClipRegion
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.shader.Rotation
import kr.toxicity.hud.shader.ScrollConfig
import kr.toxicity.hud.shader.ShaderProperty
import kr.toxicity.hud.shader.SmoothConfig
import kr.toxicity.hud.util.getShadow
import kr.toxicity.hud.util.toPosition
import kr.toxicity.hud.util.toRotation

interface HudLayout<T : HudElement> : ConditionSource, PlaceholderSource {
    val source: T
    val outline: Int
    val layer: Int
    val property: Int
    val follow: String?
    val location: PixelLocation
    val cancelIfFollowerNotExists: Boolean
    val renderScale: RenderScale
    val tick: Long
    val rotation: Rotation
    val positionX: PlaceholderBuilder<*>?
    val positionY: PlaceholderBuilder<*>?
    val positionInRotatedSpace: Boolean
    val clipInner: Double
    val clipOuter: Double
    val clipOriginX: Double?
    val clipOriginY: Double?

    /**
     * The custom clip region (`clip-rect`), or null for the classic clip only.
     */
    val clip: ClipRegion?

    /**
     * Tween settings (`smooth`) for the element's values, or null to keep them stepping per update.
     */
    val smooth: SmoothConfig?

    /**
     * The placeholder behind `clip-fill.value`: the number the boundary walks to, evaluated for every
     * player and every update. Null for a region which only clips.
     */
    val clipFillValue: PlaceholderBuilder<*>?

    /** How `clip-fill.value` is read; see [ClipRegion.UNIT_AUTO]. Never reaches the shader. */
    val clipFillUnit: Int get() = ClipRegion.UNIT_AUTO

    /**
     * A content scroll (`scroll`): the placeholder which moves the element along one axis, driven on the
     * GPU between updates. Null when the element does not scroll.
     */
    val scroll: ScrollConfig?

    /**
     * The placeholder behind `scroll.value`.
     */
    val scrollValue: PlaceholderBuilder<*>?

    interface Identifier {
        val name: String
    }

    class Impl<T : HudElement>(
        override val source: T,
        group: LayoutGroup,
        originalLoc: PixelLocation,
        yaml: YamlObject
    ) : HudLayout<T>, ConditionSource by source + ConditionSource.Impl(yaml) + group, PlaceholderSource by PlaceholderSource.Impl(yaml) {
        override val outline: Int = yaml.getShadow("outline")
        override val layer: Int = yaml.getAsInt("layer", 0)
        override val property: Int = ShaderProperty.properties(yaml["properties"]?.asArray())
        override val follow: String? = yaml["follow"]?.asString()
        override val location: PixelLocation = PixelLocation(yaml) + originalLoc + PixelLocation.hotBarHeight
        override val cancelIfFollowerNotExists: Boolean = yaml.getAsBoolean("cancel-if-follower-not-exists", true)
        override val renderScale = RenderScale.fromConfig(location, yaml)
        override val tick: Long = yaml.getAsLong("tick", 1)
        override val rotation: Rotation = yaml.toRotation(this)
        override val positionX: PlaceholderBuilder<*>? = yaml.toPosition(this, 0)
        override val positionY: PlaceholderBuilder<*>? = yaml.toPosition(this, 1)
        /**
         * `position-in-rotated-space`: the offset in `position` is expressed in the element's **rotated**
         * frame - i.e. the offset vector is rotated together with the element's `rotation`.
         * Default false = the offset is added in the screen/GUI frame (+x always points right, so rotating
         * the element never changes the direction it is shifted to).
         *
         * When true is needed: the caller computes its `position` in the element's own (unrotated) frame.
         * Example: a "map follows the view" element, whose offset is the window center measured inside the
         * texture - that offset has to rotate with the texture, otherwise turning the view slides it along
         * the screen axes.
         *
         * The former name `position-in-map-space` is still accepted: BetterHud itself has no notion of a
         * "map", the name leaked in from a minimap use case.
         */
        override val positionInRotatedSpace: Boolean =
            yaml.getAsBoolean("position-in-rotated-space", yaml.getAsBoolean("position-in-map-space", false))
        override val clipInner: Double = yaml.getAsDouble("clip-inner", 0.0).coerceAtLeast(0.0)
        override val clipOuter: Double = yaml.getAsDouble("clip", 0.0).coerceAtLeast(0.0)
        // Clip circle center (GUI/font units, same unit as clip). Null when absent - the shader then keeps
        // clipping around the element's own center (bhPivot). Both values must be present, or both are ignored.
        override val clipOriginX: Double? = yaml.clipOriginAt(0)
        override val clipOriginY: Double? = yaml.clipOriginAt(1)
        /**
         * `clip-rect: [x, y, w, h]` + `clip-radius` / `clip-soft` / `clip-fill`: a region in the element's
         * own pixels which the shader keeps, optionally walking its edge with a placeholder. It is a fixed
         * window by default - the rectangle never moves, whatever the element's content does - and
         * `clip-follow: content` turns it into a stencil that travels with the picture instead. Absent for
         * every config which predates it, and then nothing changes.
         */
        override val clip: ClipRegion? = yaml.clipRect()?.let { rect ->
            val fill = yaml["clip-fill"]?.asObject()
            // `up` and `left` name the end the boundary walks towards, which the axis alone cannot.
            val direction = fill?.getAsString("direction", "right")?.lowercase()
            ClipRegion(
                rect[0], rect[1], rect[2], rect[3],
                yaml.getAsDouble("clip-radius", 0.0).coerceAtLeast(0.0),
                yaml.getAsDouble("clip-soft", 0.0).coerceAtLeast(0.0),
                fill?.let {
                    when (direction) {
                        "up", "down" -> ClipRegion.AXIS_Y
                        "center-x", "centerx" -> ClipRegion.AXIS_CENTER_X
                        "center-y", "centery" -> ClipRegion.AXIS_CENTER_Y
                        "radial", "circle" -> ClipRegion.AXIS_RADIAL
                        "pie", "sweep" -> ClipRegion.AXIS_PIE
                        else -> ClipRegion.AXIS_X
                    }
                } ?: ClipRegion.NONE,
                (fill?.getAsBoolean("invert", false) ?: false) xor (direction == "up" || direction == "left"),
                // The region is a fixed window inside the element's own box unless the config asks for the
                // other reading, `clip-follow: content`, where the rectangle is a stencil on the picture
                // and travels with it (scroll and rotation both).
                yaml.getAsString("clip-follow", "base").equals("content", true)
            )
        }
        /**
         * `scroll: { value, axis, scale }`: the placeholder which moves the content along one axis. The
         * shader keeps it moving between updates with the rate the payload carries, which is what makes a
         * long strip (a compass) scroll continuously instead of stepping once per update.
         */
        override val scroll: ScrollConfig? = yaml.scrollConfig()
        override val scrollValue: PlaceholderBuilder<*>? = yaml.numberOf("scroll", "value", this)
        /**
         * `smooth: { duration, easing, rate }`: evaluates the element's animated values on the GPU between
         * updates. `duration` alone is accepted (`smooth: 6`).
         */
        override val smooth: SmoothConfig? = yaml.smoothConfig()
        /**
         * `clip-fill.value`: read like a `position` axis - a number placeholder, evaluated per player and
         * per update, which the shader tweens towards on the GPU.
         */
        override val clipFillValue: PlaceholderBuilder<*>? = yaml.numberOf("clip-fill", "value", this)
        override val clipFillUnit: Int = yaml["clip-fill"]?.asObject()
            ?.getAsString("unit", "auto")
            ?.let { ClipRegion.unitOf(it) }
            ?: ClipRegion.UNIT_AUTO
    }
}

/**
 * Reads `smooth` either as a number (the tween length in ticks) or as an object. Only the presence of a
 * value turns smoothing on, so a config which never mentions it keeps the old, stepping behaviour.
 */
private fun YamlObject.smoothConfig(): SmoothConfig? {
    val section = get("smooth") ?: return null
    val duration = runCatching {
        section.asObject()?.getAsDouble("duration", 6.0)
    }.getOrNull() ?: runCatching { section.asDouble() }.getOrNull() ?: 6.0
    val object0 = runCatching { section.asObject() }.getOrNull()
    val easing = object0?.get("easing")?.asString()?.let { SmoothConfig.easingOf(it) } ?: SmoothConfig.SMOOTHSTEP
    // `rate` adds the angular rate to the rotation payload; the default walks two known endpoints instead,
    // which cannot overshoot the way an extrapolated rate can.
    val rate = object0?.getAsBoolean("rate", false) ?: false
    val omega = object0?.getAsDouble("omega", 30.0) ?: 30.0
    return SmoothConfig(duration.coerceAtLeast(0.0), easing, if (rate) Math.toRadians(omega) else 0.0)
}

/**
 * Reads `clip-rect: [x, y, w, h]` (or a whitespace separated string). A malformed or missing value reads
 * as null, so the element keeps the classic behaviour instead of clipping everything away.
 */
private fun YamlObject.clipRect(): DoubleArray? {
    val rect = get("clip-rect") ?: return null
    val parts = runCatching {
        rect.asArray()?.mapNotNull { it.asString()?.toDoubleOrNull() }
            ?: rect.asString().trim().split(SPACE).mapNotNull { it.toDoubleOrNull() }
    }.getOrNull() ?: return null
    return if (parts.size >= 4) doubleArrayOf(parts[0], parts[1], parts[2], parts[3]) else null
}

/**
 * Reads `scroll: { value, axis, scale }`. `scale` is how many pixels the payload's full range means, so
 * it trades range against the step the 11 bit value has (2 * scale / 2047 pixels). Absent or without a
 * `value` reads as null, and the element keeps its old, static placement.
 */
private fun YamlObject.scrollConfig(): ScrollConfig? {
    val section = get("scroll")?.asObject() ?: return null
    if (section.get("value") == null) return null
    val axis = if (section.getAsString("axis", "x").lowercase().startsWith("y")) ScrollConfig.AXIS_Y
    else ScrollConfig.AXIS_X
    return ScrollConfig(axis, section.getAsDouble("scale", 256.0).coerceAtLeast(1.0))
}

private val SPACE = "\\s+".toRegex()

/**
 * Reads a number placeholder out of a nested section (`clip-fill.value`, `scroll.x`, ...), the same way
 * [kr.toxicity.hud.util.toPosition] reads a `position` axis. A missing value reads as null instead of
 * failing the whole element.
 */
private fun YamlObject.numberOf(section: String, key: String, source: PlaceholderSource): PlaceholderBuilder<*>? {
    val raw = get(section)?.asObject()?.get(key)?.asString() ?: return null
    return kr.toxicity.hud.manager.PlaceholderManagerImpl.find(raw, source)
        .assertNumber("this $section.$key is not a number: $raw")
}

/**
 * Reads one axis of `clip-origin`: either an array entry or a whitespace separated string (`"192 192"`).
 * Mirrors [kr.toxicity.hud.location.GuiLocation.at]; a malformed value reads as null, so the element keeps
 * clipping around its own center instead of silently clipping around (0, 0).
 */
private fun YamlObject.vectorAt(key: String, index: Int): Double? {
    val value = get(key) ?: return null
    return runCatching {
        value.asArray().elementAtOrNull(index)?.asString()?.toDoubleOrNull()
            ?: value.asString().trim().split(SPACE).getOrNull(index)?.toDoubleOrNull()
    }.getOrNull()
}

private fun YamlObject.clipOriginAt(index: Int): Double? = vectorAt("clip-origin", index)

internal fun YamlObject.rotationAnchorAt(index: Int): Double? = vectorAt("rotation-anchor", index)
