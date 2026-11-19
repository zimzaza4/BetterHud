package kr.toxicity.hud.layout

import kr.toxicity.hud.api.component.WidthComponent
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.background.HudBackground
import kr.toxicity.hud.equation.TEquation
import kr.toxicity.hud.image.LoadedImage
import kr.toxicity.hud.layout.enums.LayoutAlign
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.manager.BackgroundManager
import kr.toxicity.hud.manager.ConfigManagerImpl
import kr.toxicity.hud.manager.EncodeManager
import kr.toxicity.hud.manager.MinecraftManager
import kr.toxicity.hud.manager.TextManagerImpl
import kr.toxicity.hud.element.TextElement
import kr.toxicity.hud.pack.PackGenerator
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.ShaderGroup
import kr.toxicity.hud.text.BackgroundKey
import kr.toxicity.hud.text.HudTextData
import kr.toxicity.hud.text.ImageTextScale
import kr.toxicity.hud.util.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import java.text.DecimalFormat
import kotlin.math.roundToInt

interface TextLayout : HudLayout<TextElement> {

    companion object {
        private fun interface EmojiProvider : (TextLayout, () -> Int) -> IntKeyMap<ImageTextScale>
        private val emojiProviderMap: List<EmojiProvider> = listOf(
            EmojiProvider { layout, getter ->
                if (ConfigManagerImpl.loadMinecraftDefaultTextures) {
                    MinecraftManager.applyAll(layout, getter)
                } else emptyMap<Int, ImageTextScale>().toIntKeyMap()
            }
        )
    }

    val pattern: String
    val scale: Double
    val space: Int
    val align: LayoutAlign
    val lineAlign: LayoutAlign
    val color: TextColor
    val numberEquation: TEquation
    val numberFormat: DecimalFormat
    val disableNumberFormat: Boolean
    val useLegacyFormat: Boolean
    val legacySerializer: ComponentDeserializer
    val line: Int
    val splitWidth: Int
    val lineWidth: Int
    val forceSplit: Boolean

    val background: BackgroundInfo
    val emoji: EmojiInfo

    data class BackgroundInfo(
        val source: HudBackground?,
        val scale: Double
    )

    data class EmojiInfo(
        val location: PixelLocation,
        val scale: Double
    )

    fun identifier(shader: HudShader, ascent: Int): HudLayout.Identifier {
        return TextIdentifier(
            ShaderGroup(shader, source.id, ascent),
            this
        )
    }

    class TextIdentifier(
        val delegate: HudLayout.Identifier,
        layout: TextLayout
    ) : HudLayout.Identifier by delegate {
        val scale = layout.scale
        val background = layout.background
        val emoji = layout.emoji

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TextIdentifier) return false

            if (scale != other.scale) return false
            if (delegate != other.delegate) return false
            if (background != other.background) return false
            if (emoji != other.emoji) return false

            return true
        }

        override fun hashCode(): Int {
            var result = scale.hashCode()
            result = 31 * result + delegate.hashCode()
            result = 31 * result + background.hashCode()
            result = 31 * result + emoji.hashCode()
            return result
        }
    }

    fun startJson() = jsonArrayOf(
        jsonObjectOf(
            "type" to "space",
            "advances" to buildJsonObject {
                addProperty(" ", 4)
                if (space != 0) addProperty(TEXT_SPACE_KEY_CODEPOINT.parseChar(), space)
            }
        )
    )

    val imageCharMap: IntKeyMap<ImageTextScale>

    class Impl(
        s: String,
        override val source: TextElement,
        group: LayoutGroup,
        yamlObject: YamlObject,
        loc: PixelLocation
    ) : TextLayout, HudLayout<TextElement> by HudLayout.Impl(source, group, loc, yamlObject) {
        constructor(
            s: String,
            group: LayoutGroup,
            yamlObject: YamlObject,
            loc: PixelLocation
        ): this(
            s,
            yamlObject["name"]?.asString().ifNull { "name value not set: $s" }.let { n ->
                TextManagerImpl.getText(n).ifNull { "this text doesn't exist: $n" }
            },
            group,
            yamlObject,
            loc
        )

        override val pattern: String = yamlObject["pattern"]?.asString().ifNull { "pattern value not set: $s" }
        override val scale: Double = yamlObject.getAsDouble("scale", 1.0)
        override val space: Int = yamlObject.getAsInt("space", 0)
        override val align: LayoutAlign = yamlObject["align"]?.asString().toLayoutAlign()
        override val lineAlign: LayoutAlign = yamlObject["line-align"]?.asString().toLayoutAlign()
        override val color: TextColor = yamlObject["color"]?.asString()?.toTextColor() ?: NamedTextColor.WHITE
        override val numberEquation: TEquation = yamlObject["number-equation"]?.asString()?.let {
            TEquation(it)
        } ?: TEquation.t
        override val numberFormat: DecimalFormat = yamlObject["number-format"]?.asString()?.let {
            DecimalFormat(it)
        } ?: ConfigManagerImpl.numberFormat
        override val disableNumberFormat: Boolean = yamlObject.getAsBoolean("disable-number-format", true)
        override val background: BackgroundInfo = BackgroundInfo(
            yamlObject["background"]?.asString()?.let {
                BackgroundManager.getBackground(it)
            },
            yamlObject.getAsDouble("background-scale", scale)
        )
        override val emoji: EmojiInfo = EmojiInfo(
            yamlObject["emoji-pixel"]?.toPixelLocation() ?: PixelLocation.zero,
            yamlObject.getAsDouble("emoji-scale", 1.0).apply {
                if (this <= 0) throw RuntimeException("emoji-scale cannot be <= 0")
            }
        )
        override val useLegacyFormat: Boolean = yamlObject.getAsBoolean("use-legacy-format", ConfigManagerImpl.useLegacyFormat)
        override val legacySerializer: ComponentDeserializer = yamlObject["legacy-serializer"]?.asString()?.toLegacySerializer() ?: ConfigManagerImpl.legacySerializer

        override val line = yamlObject.getAsInt("line", 1).apply {
            if (this < 1) throw RuntimeException("line cannot be < 1: $s")
        }
        override val splitWidth = if (line == 1) Int.MAX_VALUE else yamlObject.getAsInt("split-width", 200).apply {
            if (this < 1) throw RuntimeException("split-width cannot be < 1: $s")
        }
        override val lineWidth = yamlObject.getAsInt("line-width", 10)
        override val forceSplit: Boolean = yamlObject.getAsBoolean("force-split", false)
        override val imageCharMap: IntKeyMap<ImageTextScale> = run {
            val map = source.imageTextScale.toIntKeyMap()
            var baseValue = TEXT_IMAGE_START_CODEPOINT + map.size
            val getter = {
                ++baseValue
            }
            emojiProviderMap.forEach {
                map += it(this, getter)
            }
            map
        }
    }
}

internal fun TextLayout.buildTextData(
    shader: HudShader,
    baseY: Int,
    fontFolder: List<String>,
    fontNamePrefix: String
): HudTextData {
    val scaledMap = source.charWidth.intEntries.associate { (k, v) ->
        k to v * scale
    }
    val scaledImageMap = imageCharMap.intEntries.associate { (k, v) ->
        k to v * scale * emoji.scale
    }
    val useShaderY = shader.supportsTextYOffset
    val effectiveShader = shader.withTextYOffset(useShaderY)
    val lineDy = if (useShaderY) ArrayList<Int>(line) else null
    val keys = (0..<line).map { lineIndex ->
        val yAxis = (baseY + lineIndex * lineWidth).coerceIn(-HUD_ADD_HEIGHT, HUD_ADD_HEIGHT)
        val bucketY = if (useShaderY) textYBucket(yAxis) else yAxis
        lineDy?.add(yAxis - bucketY)
        text(identifier(effectiveShader, bucketY)) {
            val array = startJson()
            source.array.forEach {
                createAscent(effectiveShader, bucketY - it.ascent(scale)) { y ->
                    array += jsonObjectOf(
                        "type" to "bitmap",
                        "file" to "$NAME_SPACE_ENCODED:${it.file}",
                        "ascent" to y,
                        "height" to (it.height * scale).roundToInt(),
                        "chars" to it.chars
                    )
                }
            }
            var imageTextIndex = TEXT_IMAGE_START_CODEPOINT + scaledImageMap.size
            val textEncoded = "${fontNamePrefix}_${lineIndex + 1}".encodeKey(EncodeManager.EncodeNamespace.FONT)
            val key = createAdventureKey(textEncoded)
            scaledImageMap.forEach { (k, v) ->
                createAscent(effectiveShader, bucketY + v.location.y + v.ascent) { y ->
                    array += jsonObjectOf(
                        "type" to "bitmap",
                        "file" to v.fileName,
                        "ascent" to y,
                        "height" to v.normalizedHeight,
                        "chars" to jsonArrayOf(k.parseChar())
                    )
                }
            }
            PackGenerator.addTask(fontFolder + "$textEncoded.json") {
                jsonObjectOf("providers" to array).toByteArray()
            }
            BackgroundKey(
                key,
                //TODO replace it to proper background in the future.
                background.source?.let {
                    fun getString(image: LoadedImage, file: String): WidthComponent {
                        val result = (++imageTextIndex).parseChar()
                        val height = (image.image.height.toDouble() * background.scale).roundToInt()
                        val div = height.toDouble() / image.image.height
                        val bgShader = effectiveShader.toBackground(it.location.opacity).withTextYOffset(useShaderY)
                        createAscent(bgShader, bucketY + it.location.y) { y ->
                            array += jsonObjectOf(
                                "type" to "bitmap",
                                "file" to "$NAME_SPACE_ENCODED:$file.png",
                                "ascent" to y,
                                "height" to height,
                                "chars" to jsonArrayOf(result)
                            )
                        }
                        return WidthComponent(
                            Component.text()
                                .content(result)
                                .append(NEGATIVE_ONE_SPACE_COMPONENT.finalizeFont().component),
                            (image.image.width.toDouble() * div).roundToInt()
                        )
                    }
                    BackgroundLayout(
                        it.location.x,
                        getString(it.left, "background_${it.id}_left".encodeKey(EncodeManager.EncodeNamespace.TEXTURES)),
                        getString(it.right, "background_${it.id}_right".encodeKey(EncodeManager.EncodeNamespace.TEXTURES)),
                        getString(it.body, "background_${it.id}_body".encodeKey(EncodeManager.EncodeNamespace.TEXTURES))
                    )
                }
            )
        }
    }
    return HudTextData(
        keys,
        (scaledMap.entries.associate { (k, v) ->
            k to v.normalizedWidth
        } + scaledImageMap.entries.associate { (k, v) ->
            k to v.normalizedWidth
        }).toIntMap(),
        scaledImageMap.map {
            it.value.name to it.key
        }.toMap(),
        splitWidth,
        lineDy
    )
}