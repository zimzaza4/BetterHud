package kr.toxicity.hud.image

import kr.toxicity.hud.manager.EncodeManager
import kr.toxicity.hud.util.encodeFile

class NamedLoadedImage(
    name: String,
    val image: LoadedImage
) {
    val name = "image_$name".encodeFile(EncodeManager.EncodeNamespace.TEXTURES)

    /**
     * Tiles when this image is bigger than the vanilla font atlas, null when it fits into one glyph.
     *
     * The parsers draw the tiles one by one - each with its own ascent and x offset - and
     * [ImageManager][kr.toxicity.hud.manager.ImageManager] ships them instead of the whole image.
     * The file name of a tile is derived from [name], so it stays unique and obfuscated the same way.
     */
    val tiles: ImageTiles? = ImageTiles.of(image.image) { column, row ->
        // `name` here is the encoded name above: derive the tiles from it before re-encoding them.
        "${name.substringBefore('.')}_${column}_${row}.png".encodeFile(EncodeManager.EncodeNamespace.TEXTURES)
    }
}
