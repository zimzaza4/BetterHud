package kr.toxicity.hud.image

import com.google.gson.JsonArray
import kr.toxicity.hud.api.component.WidthComponent
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.util.NAME_SPACE_ENCODED
import kr.toxicity.hud.util.createAscent
import kr.toxicity.hud.util.jsonArrayOf
import kr.toxicity.hud.util.jsonObjectOf
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One piece of an image, cut small enough to be uploaded as a single bitmap font glyph: [name] is its
 * file name inside the pack, [image] its pixels, always [ImageTiles.tileWidth] x [ImageTiles.tileHeight].
 */
class ImageTile(
    val name: String,
    val image: BufferedImage
)

/**
 * An image which had to be cut because one bitmap font glyph cannot be bigger than [ATLAS_SIZE]: the
 * 256x256 sheet the client stitches a bitmap font into refuses a cell which does not fit, and the
 * provider is then dropped without an error on either side.
 *
 * Every tile is padded to exactly [tileWidth] x [tileHeight], or the pieces would not line up.
 */
class ImageTiles(
    val tiles: List<ImageTile>,
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    operator fun get(column: Int, row: Int): ImageTile = tiles[row * columns + column]

    fun place(displayHeight: Int): TiledImageLayout {
        val tileDisplayHeight = (tileHeight * (displayHeight.toDouble() / sourceHeight)).roundToInt()
        val factor = tileDisplayHeight.toDouble() / tileHeight
        // One boundary per column, from the row's origin, so a fraction of a pixel cannot accumulate.
        fun boundary(column: Int) = (column * tileWidth * factor).roundToInt()
        val gridWidth = boundary(columns)
        return TiledImageLayout(
            List(tiles.size) { index ->
                val column = index % columns
                val tile = tiles[index]
                TiledImageLayout.PlacedTile(
                    tile.name,
                    boundary(column),
                    index / columns * tileDisplayHeight,
                    boundary(column + 1) - boundary(column),
                    tileDisplayHeight,
                    tile.image.glyphAdvance(tileDisplayHeight)
                )
            },
            columns,
            rows,
            // What the untouched image would have been drawn at: the grid can be wider, because the
            // last column is padded to a whole tile.
            (sourceWidth * factor).roundToInt(),
            rows * tileDisplayHeight,
            gridWidth
        )
    }

    companion object {
        /**
         * The side of `net.minecraft.client.gui.font.FontTexture`. Its sheet is 256x256 and a glyph
         * wider or taller than that is dropped by `FontTexture.Node.insert`.
         */
        const val ATLAS_SIZE = 256

        /**
         * Cuts [image] into tiles of at most [ATLAS_SIZE] pixels, or returns null when the image fits
         * into a single glyph - such an image keeps its own file name and font entry, so the pack of an
         * image which never hit the limit stays unchanged. [name] is only called when it is cut.
         */
        fun of(image: BufferedImage, name: (column: Int, row: Int) -> String): ImageTiles? {
            val rows = ceil(image.height.toDouble() / ATLAS_SIZE).toInt().coerceAtLeast(1)
            val tileHeight = ceil(image.height.toDouble() / rows).toInt()
            val gridColumns = ceil(image.width.toDouble() / ATLAS_SIZE).toInt().coerceAtLeast(1)
            if (gridColumns == 1 && rows == 1) return null
            // The client draws a tile `tileWidth * jsonHeight / cellHeight` pixels wide, while the
            // cursor which lays them out only moves by whole pixels: a multiple of the height keeps that
            // product whole at every scale, so the tiles touch instead of leaving a hairline seam.
            val tileWidth = if (gridColumns == 1) image.width
            else (ATLAS_SIZE / tileHeight).coerceAtLeast(1) * tileHeight
            val columns = ceil(image.width.toDouble() / tileWidth).toInt().coerceAtLeast(1)
            val tiles = ArrayList<ImageTile>(columns * rows)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val x = column * tileWidth
                    val y = row * tileHeight
                    val width = minOf(tileWidth, image.width - x)
                    val height = minOf(tileHeight, image.height - y)
                    if (width <= 0 || height <= 0) continue
                    val piece = image.getSubimage(x, y, width, height)
                    tiles += ImageTile(
                        name(column, row),
                        if (width == tileWidth && height == tileHeight) piece else piece.padded(tileWidth, tileHeight)
                    )
                }
            }
            return ImageTiles(tiles, columns, rows, tileWidth, tileHeight, image.width, image.height)
        }
    }
}

/**
 * Where every tile of an [ImageTiles] goes, in gui pixels, relative to the origin of the whole image.
 */
class TiledImageLayout(
    val tiles: List<PlacedTile>,
    val columns: Int,
    val rows: Int,
    val width: Int,
    val height: Int,
    /** Display width of the whole grid, padding included - what a row advances and its rewind undoes. */
    val gridWidth: Int
) {
    fun get(column: Int, row: Int): PlacedTile = tiles[row * columns + column]

    class PlacedTile(
        val name: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        /** What `BitmapProvider` derives from this piece; shorter than [width] when it ends with transparency. */
        val advance: Int
    ) {
        val padding: Int get() = width - advance

        fun centerX() = x + width / 2.0
        fun centerY() = y + height / 2.0
    }

    fun shaderOf(shader: HudShader, tile: PlacedTile): HudShader = if (shader.pivotsAroundElementCenter) {
        shader.copy(
            rotationHalfX = tile.width / 2.0,
            rotationHalfY = tile.height / 2.0,
            rotationAnchorX = width / 2.0 - tile.centerX(),
            rotationAnchorY = height / 2.0 - tile.centerY()
        )
    } else shader

    /**
     * Emits the bitmap provider of every tile into [array] and returns the component drawing them as one
     * image: a tile's ascent is the y of the picture, the spaces around it the x of it.
     */
    fun toWidthComponent(
        shader: HudShader,
        font: Key,
        array: JsonArray?,
        baseAscent: Int,
        newChar: () -> String,
        space: (Int) -> String
    ): WidthComponent {
        val builder = Component.text().font(font)
        val content = StringBuilder()
        var index = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val tile = tiles[index++]
                val char = newChar()
                content.append(char)
                if (tile.padding != 0) content.append(space(tile.padding))
                array?.let { target ->
                    createAscent(shaderOf(shader, tile), baseAscent + tile.y) { y ->
                        target.add(
                            jsonObjectOf(
                                "type" to "bitmap",
                                "file" to "$NAME_SPACE_ENCODED:${tile.name}",
                                "ascent" to y,
                                "height" to tile.height,
                                "chars" to jsonArrayOf(char)
                            )
                        )
                    }
                }
            }
            // Every row is drawn from the left edge of the image: undo what the row advanced.
            if (row != rows - 1) content.append(space(-gridWidth))
        }
        // Drop the padding of the last column: the element has to be as wide as the untouched image,
        // which is what the layout, the clip and a rotation pivot are measured against.
        if (gridWidth != width) content.append(space(width - gridWidth))
        return WidthComponent(builder.content(content.toString()), width)
    }
}

/**
 * Copies [this] piece into the top-left corner of an empty tile of the wanted size; the pieces at the
 * edge have to be padded there, or the client draws them at another scale.
 */
private fun BufferedImage.padded(width: Int, height: Int): BufferedImage {
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    graphics.drawImage(this, 0, 0, null)
    graphics.dispose()
    return target
}

/**
 * The advance `BitmapProvider` derives for a glyph covering all of [this] tile: `(int)(0.5 + actual *
 * height / cellHeight) + 1`, where `actual` is the last column with any alpha.
 */
private fun BufferedImage.glyphAdvance(displayHeight: Int): Int {
    val alpha = alphaRaster
    var actual = 0
    if (alpha != null) {
        loop@ for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                if (alpha.getSample(x, y, 0) != 0) {
                    actual = x + 1
                    break@loop
                }
            }
        }
    } else {
        // No alpha channel at all: the png is opaque everywhere, so every column counts.
        loop@ for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                if (getRGB(x, y) ushr 24 != 0) {
                    actual = x + 1
                    break@loop
                }
            }
        }
    }
    val factor = displayHeight.toFloat() / height
    return ((actual * factor).toDouble() + 0.5).toInt() + 1
}
