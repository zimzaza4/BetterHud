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
 * One piece of an image, cut small enough to be uploaded as a single bitmap font glyph.
 *
 * @param name texture file name (with its extension) inside the generated resource pack
 * @param image the pixels of this piece, always [ImageTiles.tileWidth] x [ImageTiles.tileHeight]
 */
class ImageTile(
    val name: String,
    val image: BufferedImage
)

/**
 * An image which had to be cut because one bitmap font glyph cannot be bigger than [ATLAS_SIZE].
 *
 * Every glyph of a bitmap provider is uploaded as one cell of a 256x256 font sheet
 * (`net.minecraft.client.gui.font.FontTexture`), and the quadtree of that sheet
 * (`FontTexture.Node.insert`) refuses a cell which does not fit. The provider then bails out and the
 * client draws the missing-glyph box instead: that is why an image wider or taller than 256 pixels
 * silently disappears.
 *
 * The cut is a **uniform grid**: every tile is exactly [tileWidth] x [tileHeight], the tiles at the
 * right and bottom edge being padded with transparency, and the size is picked so that a tile never
 * exceeds the atlas.
 *
 * The grid matters for two reasons. A bitmap glyph is scaled by `json height / cell height`, so a
 * tile which is shorter than its neighbours would be drawn at a slightly different scale - the grid
 * keeps every tile on one scale. And the tiles of a cut image are put back together by the caller
 * with space glyphs, which only stay in line as long as they all share the same width.
 */
class ImageTiles(
    /** Tiles in row-major order; every one of them is [tileWidth] x [tileHeight]. */
    val tiles: List<ImageTile>,
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    /** Size of the image which was cut. */
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    operator fun get(column: Int, row: Int): ImageTile = tiles[row * columns + column]

    /**
     * Computes where every tile lands when the whole image is drawn [displayHeight] gui pixels tall.
     *
     * The client scales a tile by its own JSON height over its cell height, so the display size of a
     * tile follows that same factor - the layout never assumes a scale the client is not going to use.
     */
    fun place(displayHeight: Int): TiledImageLayout {
        val tileDisplayHeight = (tileHeight * (displayHeight.toDouble() / sourceHeight)).roundToInt()
        val factor = tileDisplayHeight.toDouble() / tileHeight
        // one rounding per boundary instead of one per tile: the columns cannot drift, and the whole
        // grid stays as wide as the untouched image would have been
        fun boundary(column: Int) = (column * tileWidth * factor).roundToInt()
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
            boundary(columns),
            rows * tileDisplayHeight
        )
    }

    companion object {
        /**
         * The side of `net.minecraft.client.gui.font.FontTexture`. Its sheet is 256x256 and a glyph
         * wider or taller than that is dropped by `FontTexture.Node.insert`.
         */
        const val ATLAS_SIZE = 256

        /**
         * Cuts [image] into a grid of tiles of at most [ATLAS_SIZE] pixels, or returns null when the
         * image already fits into a single glyph.
         *
         * Nothing is cut in that case - the caller keeps its own file name and its own font entry - so
         * the pack of an image which never hit the limit stays byte for byte what it was.
         *
         * @param name file name of a tile, called only when the image is actually cut
         */
        fun of(image: BufferedImage, name: (column: Int, row: Int) -> String): ImageTiles? {
            val columns = ceil(image.width.toDouble() / ATLAS_SIZE).toInt().coerceAtLeast(1)
            val rows = ceil(image.height.toDouble() / ATLAS_SIZE).toInt().coerceAtLeast(1)
            if (columns == 1 && rows == 1) return null
            val tileWidth = ceil(image.width.toDouble() / columns).toInt()
            val tileHeight = ceil(image.height.toDouble() / rows).toInt()
            val tiles = ArrayList<ImageTile>(columns * rows)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val x = column * tileWidth
                    val y = row * tileHeight
                    val width = minOf(tileWidth, image.width - x)
                    val height = minOf(tileHeight, image.height - y)
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
 *
 * The origin is the top-left corner the untouched image would have used: the y of a tile is what the
 * caller adds to the glyph's ascent, and the x is what the spaces around the glyph have to add up to.
 */
class TiledImageLayout(
    /** Tiles in row-major order. */
    val tiles: List<PlacedTile>,
    val columns: Int,
    val rows: Int,
    /** Display width of the whole image, close to what the untouched one would have reported. */
    val width: Int,
    /** Display height of the whole image, the padding of the last row included. */
    val height: Int
) {
    fun get(column: Int, row: Int): PlacedTile = tiles[row * columns + column]

    class PlacedTile(
        /** Texture file name of this piece. */
        val name: String,
        /** Display x of the left edge, relative to the image origin. */
        val x: Int,
        /** Display y of the top edge, relative to the image origin; add it to the glyph's ascent. */
        val y: Int,
        val width: Int,
        val height: Int,
        /**
         * The advance the vanilla bitmap provider derives from this piece
         * (`BitmapProvider.Definition.getActualGlyphWidth`): the last column which contains anything,
         * scaled and truncated the same way. It is shorter than [width] as soon as the piece ends with
         * transparency, so the caller has to pad it back with a space glyph.
         */
        val advance: Int
    ) {
        /** Space glyph which makes this piece occupy exactly [width] pixels. */
        val padding: Int get() = width - advance

        fun centerX() = x + width / 2.0
        fun centerY() = y + height / 2.0
    }

    /**
     * The shader [tile] is drawn with.
     *
     * A rotated (or clipped) element pivots around its own centre, and a single tile cannot know where
     * that is: such a tile gets a shader of its own whose pivot is the centre of the whole image, or
     * the pieces would turn - and clip - around different points and tear apart.
     */
    fun shaderOf(shader: HudShader, tile: PlacedTile): HudShader = if (shader.pivotsAroundElementCenter) {
        shader.copy(
            rotationHalfX = tile.width / 2.0,
            rotationHalfY = tile.height / 2.0,
            rotationAnchorX = width / 2.0 - tile.centerX(),
            rotationAnchorY = height / 2.0 - tile.centerY()
        )
    } else shader

    /**
     * Emits the bitmap font provider of every tile into [array] and returns the width component which
     * draws them as one image.
     *
     * A tile keeps its own ascent - that is the y of the picture - and the space glyphs around it are
     * the x of it: the vanilla provider derives the advance of a glyph from its last column which
     * contains anything, so a tile ending with transparency is padded back to its own width.
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
            if (row != rows - 1) content.append(space(-width))
        }
        return WidthComponent(builder.content(content.toString()), width)
    }
}

/**
 * Copies [this] piece into the top-left corner of an empty tile of the wanted size.
 *
 * The client scales a glyph by its cell's size, so the pieces at the right and bottom edge of an image
 * have to be padded to the size of the others or they would be drawn on a different scale.
 */
private fun BufferedImage.padded(width: Int, height: Int): BufferedImage {
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    graphics.drawImage(this, 0, 0, null)
    graphics.dispose()
    return target
}

/**
 * The advance the vanilla bitmap provider derives for a glyph covering all of [this] tile.
 *
 * `BitmapProvider.Definition.load` scans the cell from its last column and keeps the first one which
 * has any alpha, then stores `(int)(0.5 + width * height / cellHeight) + 1` as the advance. The cell
 * here is the whole tile, so this has to be mirrored exactly - a pixel too much or too little would
 * shift every following tile.
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
