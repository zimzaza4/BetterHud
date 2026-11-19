import kr.toxicity.hud.manager.TextManagerImpl
import kr.toxicity.hud.manager.collectWhitelistCodepoints
import kr.toxicity.hud.manager.loadChinaCommonCodepoints
import kr.toxicity.hud.util.toByteArray
import kr.toxicity.hud.yaml.YamlObjectImpl
import org.yaml.snakeyaml.Yaml
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontOptimizationTest {

    private fun section(yaml: String): YamlObjectImpl {
        val loaded = Yaml().load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlObjectImpl("", LinkedHashMap(loaded))
    }

    private fun assertExactPngRoundTrip(image: BufferedImage): ByteArray {
        val encoded = image.toByteArray()
        val decoded = ImageIO.read(ByteArrayInputStream(encoded))
        assertNotNull(decoded)
        assertEquals(image.width, decoded.width)
        assertEquals(image.height, decoded.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                assertEquals(image.getRGB(x, y), decoded.getRGB(x, y), "Mismatch at ($x, $y)")
            }
        }
        return encoded
    }

    private fun pngHeaderBitDepthAndColorType(png: ByteArray): Pair<Int, Int> {
        // PNG signature (8) + IHDR length (4) + "IHDR" (4) -> width (4) + height (4) + bitDepth (1) + colorType (1)
        assertEquals('I'.code.toByte(), png[12])
        assertEquals('H'.code.toByte(), png[13])
        return (png[24].toInt() and 0xFF) to (png[25].toInt() and 0xFF)
    }

    @Test
    fun testMonochromeGlyphSheetEncodesAsLossless1BitIndexedPng() {
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                if (((x * 7 + y * 13) % 5) == 0) {
                    img.setRGB(x, y, -1)
                }
            }
        }
        val compact = assertExactPngRoundTrip(img)
        val rawOut = ByteArrayOutputStream()
        ImageIO.write(img, "png", rawOut)
        val raw = rawOut.toByteArray()

        val (bitDepth, colorType) = pngHeaderBitDepthAndColorType(compact)
        assertEquals(1, bitDepth)
        assertEquals(3, colorType)
        assertTrue(compact.size < raw.size, "Expected indexed (${compact.size}) < raw (${raw.size})")
    }

    @Test
    fun testAntiAliasedSingleColorAndTrueColorImagesRoundTripLosslessly() {
        // Single fully-transparent image
        assertExactPngRoundTrip(BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB))

        // Translucent anti-aliased palette (10 colors <= 16 -> 4-bit indexed with tRNS)
        val aa = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val alphas = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 240, 255)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val a = alphas[(x + y) % alphas.size]
                aa.setRGB(x, y, if (a == 0) 0 else (a shl 24) or 0xFFFFFF)
            }
        }
        val aaEncoded = assertExactPngRoundTrip(aa)
        val (bitDepth, colorType) = pngHeaderBitDepthAndColorType(aaEncoded)
        assertEquals(4, bitDepth)
        assertEquals(3, colorType)

        // >256 unique colors -> lossless fallback to 32-bit RGBA
        val rich = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                rich.setRGB(x, y, (0xFF shl 24) or ((x * 8) shl 16) or ((y * 8) shl 8) or ((x + y) * 4))
            }
        }
        assertExactPngRoundTrip(rich)
    }

    @Test
    fun testChinaCommonResourceLoads3500IdeographsAndCjkPunctuation() {
        val file = listOf("../common-resources/china_common.txt", "common-resources/china_common.txt")
            .map(::File)
            .firstOrNull { it.isFile }
        assertNotNull(file, "china_common.txt not found")
        val set = file.inputStream().use(::loadChinaCommonCodepoints)
        for (ch in listOf('一', '战', '斗', '击', '杀', '生', '命', '护', '甲', '弹', '药', '。', '，', '（', '—')) {
            assertTrue(set.contains(ch.code), "Missing expected common char '$ch'")
        }
        assertFalse(set.contains('龘'.code))
        assertFalse(set.contains('齉'.code))
        assertTrue(set.size >= 3500)

        val chinaResolved = TextManagerImpl.resolveLanguageCodepoints("china", set)
        val chinaAlias = TextManagerImpl.resolveLanguageCodepoints("china_common", set)
        val chinaAll = TextManagerImpl.resolveLanguageCodepoints("china_all")
        assertNotNull(chinaResolved)
        assertNotNull(chinaAlias)
        assertNotNull(chinaAll)
        assertEquals(set, chinaResolved)
        assertEquals(set, chinaAlias)
        assertTrue(chinaAll.contains('龘'.code))
        assertTrue(chinaAll.size > 20_000)
    }

    @Test
    fun testWhitelistCollectsInlineAndDirectoryFilesSafely() {
        assertNull(collectWhitelistCodepoints(section("scale: 16"), File(".")))

        val tmp = Files.createTempDirectory("bh-whitelist-test").toFile()
        try {
            val langDir = File(tmp, "lang").apply { mkdirs() }
            File(langDir, "zh_CN.yml").writeText("title: \"暗区撤离•撤离倒计时\"\n", Charsets.UTF_8)
            File(langDir, "ignored.png").writeText("龘", Charsets.UTF_8)
            File(tmp, "extra.txt").writeText("★☆", Charsets.UTF_8)

            val warnings = mutableListOf<String>()
            val cfg = section(
                """
                whitelist:
                  - "◆■"
                whitelist-files:
                  - "lang"
                  - "extra.txt"
                  - "missing.txt"
                """.trimIndent()
            )
            val set = collectWhitelistCodepoints(cfg, tmp) { warnings += it }
            assertNotNull(set)
            for (ch in listOf('◆', '■', '暗', '区', '撤', '离', '•', '★', '☆')) {
                assertTrue(set.contains(ch.code), "Missing whitelisted char '$ch'")
            }
            assertFalse(set.contains('龘'.code))
            assertEquals(1, warnings.size)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
