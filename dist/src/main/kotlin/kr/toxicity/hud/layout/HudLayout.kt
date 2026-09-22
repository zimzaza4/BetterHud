package kr.toxicity.hud.layout

import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.element.HudElement
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.placeholder.ConditionSource
import kr.toxicity.hud.placeholder.PlaceholderBuilder
import kr.toxicity.hud.placeholder.PlaceholderSource
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.shader.Rotation
import kr.toxicity.hud.shader.ShaderProperty
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
    }
}