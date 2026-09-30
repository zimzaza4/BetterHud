import kr.toxicity.hud.image.ImageTiles
import kr.toxicity.hud.location.GuiLocation
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.RenderScale
import kr.toxicity.hud.util.toByteArray
import net.kyori.adventure.key.Key
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.TreeMap
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImageSplitTest {

    private companion object {
        const val ATLAS = 256

        val FONT: Key = Key.key("betterhud", "test")
        val SCALE_ONE = RenderScale(PixelLocation.zero, RenderScale.Scale(1.0, 1.0, false))

        /** A plain image, opaque at every pixel the predicate accepts. */
        fun image(width: Int, height: Int, opaque: (x: Int, y: Int) -> Boolean = { _, _ -> true }) =
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        if (opaque(x, y)) setRGB(x, y, 0xFFFFFFFF.toInt())
                    }
                }
            }

        /** The width the vanilla provider would see, read the way its own scan reads the png. */
        fun lastOpaqueColumn(image: BufferedImage): Int {
            for (x in image.width - 1 downTo 0) {
                for (y in 0 until image.height) {
                    if (image.getRGB(x, y) ushr 24 != 0) return x + 1
                }
            }
            return 0
        }

        fun shader(
            rotationDegree: Double = 0.0,
            rotationDynamic: Boolean = false,
            clipOuter: Double = 0.0,
            halfX: Double = 0.0,
            halfY: Double = 0.0
        ) = HudShader(
            GuiLocation(0.0, 0.0),
            SCALE_ONE,
            0,
            0,
            1.0,
            0,
            rotationDegree,
            rotationDynamic,
            false,
            halfX,
            halfY,
            false,
            0.0,
            clipOuter
        )
    }

    @Test
    fun testImageWhichFitsIsNotCut() {
        listOf(1 to 1, 256 to 256, ATLAS to 1, 1 to ATLAS, 255 to 200).forEach { (width, height) ->
            assertNull(
                ImageTiles.of(image(width, height)) { _, _ -> error("nothing to name when nothing is cut") },
                "a ${width}x$height image fits into one glyph and must keep its own texture"
            )
        }
    }

    @Test
    fun testCutKeepsTheSourceGrid() {
        // 300 x 100 needs two columns; the cell height is the whole image, and the cell width is the
        // largest multiple of it which still fits the atlas (200), so that what the client draws a tile
        // at is a whole number of pixels at every scale
        val tiles = ImageTiles.of(image(300, 100)) { column, row -> "$column-$row" }!!
        assertEquals(2, tiles.columns)
        assertEquals(1, tiles.rows)
        assertEquals(200, tiles.tileWidth)
        assertEquals(100, tiles.tileHeight)
        assertEquals(300, tiles.sourceWidth)
        assertEquals(100, tiles.sourceHeight)
        assertEquals(200, tiles[0, 0].image.width)
        assertEquals(100, tiles[0, 0].image.height)
        assertEquals("0-0", tiles[0, 0].name)
        assertEquals("1-0", tiles[1, 0].name)
        // the second column is short, and is padded out to a whole cell
        assertEquals(200, tiles[1, 0].image.width)
        assertEquals(0, tiles[1, 0].image.getRGB(150, 50) ushr 24)
    }

    @Test
    fun testEdgeTilesArePaddedToTheGrid() {
        // 301 x 101 is cut into cells of 202 x 101: the last column is one pixel short (padded with
        // transparency) - a shorter cell would be drawn on another scale
        val tiles = ImageTiles.of(image(301, 101)) { _, _ -> "tile" }!!
        assertEquals(2, tiles.columns)
        assertEquals(1, tiles.rows)
        tiles.tiles.forEach {
            assertEquals(202, it.image.width)
            assertEquals(101, it.image.height)
        }
        val last = tiles[1, 0].image
        for (y in 0 until last.height) {
            assertEquals(0, last.getRGB(201, y) ushr 24, "the padding of the last column has to be transparent")
        }
        val tall = ImageTiles.of(image(100, 301)) { _, _ -> "tile" }!!
        assertEquals(2, tall.rows)
        for (x in 0 until 100) {
            assertEquals(0, tall[0, 1].image.getRGB(x, 150) ushr 24, "the padding of the last row has to be transparent")
        }
    }

    @Test
    fun testNoTileExceedsTheAtlas() {
        listOf(257 to 257, 512 to 512, 1024 to 700, 700 to 1024, 4096 to 17).forEach { (width, height) ->
            val tiles = ImageTiles.of(image(width, height)) { _, _ -> "tile" }!!
            tiles.tiles.forEach {
                assertTrue(
                    it.image.width <= ATLAS && it.image.height <= ATLAS,
                    "$width x $height was cut into a ${it.image.width}x${it.image.height} tile"
                )
            }
            assertEquals(tiles.columns * tiles.rows, tiles.tiles.size)
            // the grid has to cover the whole image, and its last row and column have to be used
            assertTrue(tiles.columns * tiles.tileWidth >= width, "the grid does not reach the right edge")
            assertTrue((tiles.columns - 1) * tiles.tileWidth < width, "the last column is empty")
            assertTrue(tiles.rows * tiles.tileHeight >= height, "the grid does not reach the bottom edge")
            assertTrue((tiles.rows - 1) * tiles.tileHeight < height, "the last row is empty")
            assertEquals(width, tiles.sourceWidth)
            assertEquals(height, tiles.sourceHeight)
        }
    }

    @Test
    fun testTheTilesRebuildTheSourceImage() {
        val source = image(301, 261) { x, y -> (x + 2 * y) % 3 != 0 }
        val tiles = ImageTiles.of(source) { _, _ -> "tile" }!!
        for (row in 0 until tiles.rows) {
            for (column in 0 until tiles.columns) {
                val tile = tiles[column, row].image
                for (y in 0 until tile.height) {
                    for (x in 0 until tile.width) {
                        val sourceX = column * tiles.tileWidth + x
                        val sourceY = row * tiles.tileHeight + y
                        val expected = if (sourceX < source.width && sourceY < source.height) {
                            source.getRGB(sourceX, sourceY)
                        } else 0 // everything the source does not cover stays transparent
                        assertEquals(expected, tile.getRGB(x, y), "pixel $sourceX, $sourceY")
                    }
                }
            }
        }
    }

    @Test
    fun testPlacementSharesEveryBoundary() {
        val tiles = ImageTiles.of(image(700, 400)) { _, _ -> "tile" }!!
        val layout = tiles.place(350)
        // the client scales a tile by its own height, so the layout may not assume another scale
        val tileHeight = (tiles.tileHeight * (350.0 / 400)).roundToInt()
        val factor = tileHeight.toDouble() / tiles.tileHeight
        fun boundary(column: Int) = (column * tiles.tileWidth * factor).roundToInt()
        assertEquals(boundary(tiles.columns), layout.gridWidth)
        assertEquals(tiles.rows * tileHeight, layout.height)
        // what the untouched image would have been drawn at: the grid may be wider than that
        assertEquals((700.0 * tileHeight / tiles.tileHeight).roundToInt(), layout.width)
        assertTrue(layout.width <= layout.gridWidth)
        for (row in 0 until layout.rows) {
            for (column in 0 until layout.columns) {
                val tile = layout.get(column, row)
                assertEquals(boundary(column), tile.x)
                assertEquals(boundary(column + 1) - boundary(column), tile.width)
                assertEquals(row * tileHeight, tile.y)
                assertEquals(tileHeight, tile.height)
                if (column > 0) {
                    val left = layout.get(column - 1, row)
                    assertEquals(
                        left.x + left.width,
                        tile.x,
                        "column $column of row $row must start where the previous one ends"
                    )
                }
                if (row > 0) {
                    val up = layout.get(column, row - 1)
                    assertEquals(up.y + up.height, tile.y, "row $row must start where the row above ends")
                }
            }
        }
        val last = layout.get(layout.columns - 1, layout.rows - 1)
        assertEquals(layout.gridWidth, last.x + last.width)
        assertEquals(layout.height, last.y + last.height)
    }

    /**
     * The reason the tile width is a multiple of the cell height.
     *
     * The client draws a tile `tileWidth * jsonHeight / cellHeight` wide, and the text cursor which
     * puts the tiles next to each other can only move by whole pixels. If that product is not a whole
     * number the two disagree by a fraction of a pixel on every column boundary - a hairline seam
     * inside the picture, which is far easier to spot than the same rounding between two glyphs.
     *
     * With the rule in [ImageTiles.of] the product is always whole, at every scale, so the mismatch
     * has to be exactly zero everywhere - not merely small.
     */
    @Test
    fun testEveryScaleDrawsTilesOnWholePixels() {
        val sources = listOf(600 to 64, 512 to 512, 260 to 16, 64 to 600, 301 to 101, 700 to 400, 4096 to 17)
        val scales = listOf(0.15, 0.25, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 1.1, 1.3, 1.5, 1.6, 2.4)
        var checked = 0
        for ((width, height) in sources) {
            val tiles = ImageTiles.of(image(width, height)) { _, _ -> "tile" } ?: continue
            for (scale in scales) {
                val displayHeight = (height * scale).roundToInt()
                if (displayHeight <= 0) continue
                val layout = tiles.place(displayHeight)
                val factor = layout.get(0, 0).height.toDouble() / tiles.tileHeight
                val drawnWidth = tiles.tileWidth * factor
                // a single column has no seam to leave: its tile only meets whatever comes after the
                // element, where vanilla rounds the same way for any glyph. Two columns and up must
                // line up exactly, because a gap there is a gap inside the picture.
                if (tiles.columns > 1) {
                    assertTrue(
                        abs(drawnWidth - drawnWidth.roundToInt()) < 1e-6,
                        "$width x $height at $scale draws a tile $drawnWidth px wide - a fraction of a pixel"
                    )
                }
                for (row in 0 until layout.rows) {
                    for (column in 0 until layout.columns) {
                        val tile = layout.get(column, row)
                        if (tiles.columns > 1) {
                            assertEquals(
                                drawnWidth,
                                tile.width.toDouble(),
                                1e-6,
                                "$width x $height at $scale: column $column draws $drawnWidth px into a slot of ${tile.width}"
                            )
                        }
                        if (column > 0) {
                            assertEquals(
                                layout.get(column - 1, row).x + layout.get(column - 1, row).width,
                                tile.x,
                                "the tiles of a row have to touch, or there is a seam"
                            )
                        }
                    }
                }
                checked++
            }
        }
        assertTrue(checked > 50, "the case list stopped covering enough combinations")
    }

    @Test
    fun testWideShortImageStaysOnOneScale() {
        // the piece which motivated the uniform grid: a short image is scaled by round(height / cell
        // height), which is coarse for a small cell - the layout has to follow that same factor, or
        // every tile ends short of its slot and the picture falls apart
        val tiles = ImageTiles.of(image(500, 8)) { _, _ -> "tile" }!!
        val layout = tiles.place(2)
        assertEquals(2, layout.columns)
        assertEquals(1, layout.rows)
        val tile = layout.get(0, 0)
        assertEquals(2, tile.height)
        // the cell is 256 x 8, so a tile is drawn 256 * 2 / 8 = 64 px wide - whole, and identical for
        // both columns, which is exactly what the rule is for
        assertEquals(64, tile.width)
        assertEquals(64, layout.get(1, 0).width)
        assertEquals(128, layout.gridWidth)
        assertEquals(layout.gridWidth, layout.get(0, 0).width + layout.get(1, 0).width)
        // the untouched image would have been round(500 * 2 / 8) = 125 wide
        assertEquals(125, layout.width)
    }

    @Test
    fun testEveryRowEndsOnTheRightEdge() {
        val tiles = ImageTiles.of(image(600, 600)) { _, _ -> "tile" }!!
        val layout = tiles.place(300)
        assertEquals(300, layout.width)
        for (row in 0 until layout.rows) {
            var cursor = 0
            for (column in 0 until layout.columns) {
                val tile = layout.get(column, row)
                // what the vanilla provider advances, plus the space glyph padding it back
                cursor += tile.advance + tile.padding
                assertEquals(tile.x + tile.width, cursor, "column $column of row $row lands on its own right edge")
            }
            assertEquals(layout.width, cursor, "row $row has to end on the image's right edge")
        }
    }

    @Test
    fun testAdvanceMirrorsTheVanillaProvider() {
        // the first tile (x 0..249) is opaque up to x = 99, the second one is fully transparent
        val tiles = ImageTiles.of(image(300, 10) { x, _ -> x < 100 }) { _, _ -> "tile" }!!
        val layout = tiles.place(10)
        val first = layout.get(0, 0)
        assertEquals(250, first.width)
        // BitmapProvider: (int)(0.5 + 100 * height / cellHeight) + 1
        assertEquals(101, first.advance)
        assertEquals(149, first.padding)
        val second = layout.get(1, 0)
        assertEquals(250, second.width)
        // nothing opaque at all: (int)(0.5 + 0) + 1
        assertEquals(1, second.advance)
        assertEquals(249, second.padding)
    }

    @Test
    fun testPiecesKeepTheirAlphaThroughThePng() {
        // the client scans the written png, not the in-memory image: the advance must survive it
        val tiles = ImageTiles.of(image(600, 8) { x, _ -> x % 5 != 0 }) { _, _ -> "tile" }!!
        val layout = tiles.place(4)
        tiles.tiles.forEachIndexed { index, tile ->
            val written = ImageIO.read(ByteArrayInputStream(tile.image.toByteArray()))
            assertEquals(tile.image.width, written.width)
            assertEquals(tile.image.height, written.height)
            assertEquals(
                lastOpaqueColumn(tile.image),
                lastOpaqueColumn(written),
                "tile $index has to keep its alpha through the png"
            )
            val tileHeight = layout.tiles[index].height
            val factor = tileHeight.toFloat() / written.height
            val expected = ((lastOpaqueColumn(written) * factor).toDouble() + 0.5).toInt() + 1
            assertEquals(expected, layout.tiles[index].advance, "tile $index")
        }
    }

    @Test
    fun testCompositionPadsAndRewinds() {
        val tiles = ImageTiles.of(image(300, 600)) { _, _ -> "tile" }!!
        val layout = tiles.place(600)
        assertEquals(2, layout.columns)
        assertEquals(3, layout.rows)
        assertEquals(400, layout.gridWidth)
        assertEquals(300, layout.width)
        var index = 0
        val component = layout.toWidthComponent(shader(), FONT, null, 100, { "c${index++}" }, { "<$it>" })
        // every tile is fully opaque, so the vanilla advance is one longer than the tile: the -1 space
        // (the same trick the untouched code uses) pads it back; every row then rewinds to the left
        // edge, and the trailing space trims the padded last column off the element's width
        assertEquals(
            "c0<-1>c1<99><-400>c2<-1>c3<99><-400>c4<-1>c5<99><-100>",
            component.component.build().content()
        )
        assertEquals(300, component.width)
    }

    @Test
    fun testCompositionSkipsAnEmptyPadding() {
        // tile 0 is opaque up to x = 252 of its 256: the advance comes out exactly as wide as the
        // tile, so there is nothing to pad back and no space is written
        val tiles = ImageTiles.of(image(300, 8) { x, _ -> x < 253 }) { _, _ -> "tile" }!!
        val layout = tiles.place(4)
        val first = layout.get(0, 0)
        assertEquals(128, first.width)
        assertEquals(128, first.advance)
        assertEquals(0, first.padding)
        val second = layout.get(1, 0)
        assertEquals(128, second.width)
        assertEquals(1, second.advance)
        assertEquals(127, second.padding)
        // the grid is 256 wide, the image is round(300 * 0.5) = 150: the difference is trimmed
        assertEquals(256, layout.gridWidth)
        assertEquals(150, layout.width)
        var index = 0
        val component = layout.toWidthComponent(shader(), FONT, null, 0, { "c${index++}" }, { "<$it>" })
        assertEquals("c0c1<127><-106>", component.component.build().content())
        assertEquals(150, component.width)
    }

    @Test
    fun testTilesOfACutImageShareOnePivot() {
        val tiles = ImageTiles.of(image(600, 400)) { _, _ -> "tile" }!!
        val layout = tiles.place(400)
        val plain = shader()
        val rotating = shader(rotationDegree = 45.0, halfX = 300.0, halfY = 200.0)

        // an element which does not turn keeps the very same shader for every tile
        layout.tiles.forEach { assertSame(plain, layout.shaderOf(plain, it)) }

        // a turning one gives every tile the offset from its own centre to the element's centre
        val shaders = TreeMap<HudShader, Int>()
        layout.tiles.forEachIndexed { index, tile ->
            val tileShader = layout.shaderOf(rotating, tile)
            assertTrue(tileShader.pivotsAroundElementCenter)
            assertEquals(tile.width / 2.0, tileShader.rotationHalfX)
            assertEquals(tile.height / 2.0, tileShader.rotationHalfY)
            assertEquals(layout.width / 2.0 - tile.centerX(), tileShader.rotationAnchorX)
            assertEquals(layout.height / 2.0 - tile.centerY(), tileShader.rotationAnchorY)
            // the pivot every tile ends up with has to be the centre of the whole image
            assertEquals(
                layout.width / 2.0,
                tile.x + tileShader.rotationHalfX + tileShader.rotationAnchorX,
                "tile $index must turn around the element's centre"
            )
            assertEquals(
                layout.height / 2.0,
                tile.y + tileShader.rotationHalfY + tileShader.rotationAnchorY,
                "tile $index must turn around the element's centre"
            )
            // ... and the TreeMap of the shader manager must not merge two different tiles
            shaders[tileShader] = index
        }
        assertEquals(layout.tiles.size, shaders.size)
    }

    @Test
    fun testOnlyTurningElementsNeedAPerTilePivot() {
        assertTrue(shader(rotationDegree = 1.0).pivotsAroundElementCenter)
        assertTrue(shader(rotationDynamic = true).pivotsAroundElementCenter)
        assertTrue(shader(clipOuter = 8.0).pivotsAroundElementCenter)
        assertFalse(shader().pivotsAroundElementCenter)
        // a payload which only moves the element around does not read the pivot
        assertFalse(
            HudShader(GuiLocation(0.0, 0.0), SCALE_ONE, 0, 0, 1.0, 0, positionDynamic = true)
                .pivotsAroundElementCenter
        )
    }

    @Test
    fun testAnchorIsPartOfShaderIdentity() {
        val one = shader(rotationDegree = 45.0).copy(rotationAnchorX = 10.0, rotationAnchorY = 20.0)
        val other = shader(rotationDegree = 45.0).copy(rotationAnchorX = 10.0, rotationAnchorY = 21.0)
        assertNotEquals(one, other)
        assertNotEquals(0, one.compareTo(other))
    }
}
