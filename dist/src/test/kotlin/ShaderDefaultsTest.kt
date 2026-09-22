import kr.toxicity.hud.api.manager.ShaderManager.ShaderType
import kr.toxicity.hud.api.manager.ShaderManager.ShaderType.Bundled
import kotlin.test.Test
import kotlin.test.assertEquals

/** The decision table of `BetterHud/shaders` (see [ShaderType.decide]). */
class ShaderDefaultsTest {

    @Test
    fun testMissingCopy() {
        assertEquals(Bundled.EXTRACT, ShaderType.decide(null, null, "b"))
        assertEquals(Bundled.EXTRACT, ShaderType.decide("b", null, "b"))
    }

    @Test
    fun testCurrentCopy() {
        // identical, or already compared against this default (an edit stays without a second warning)
        assertEquals(Bundled.KEEP, ShaderType.decide("b", "b", "b"))
        assertEquals(Bundled.KEEP, ShaderType.decide(null, "b", "b"))
        assertEquals(Bundled.KEEP, ShaderType.decide("b", "mine", "b"))
    }

    @Test
    fun testUntouchedOutdatedCopy() {
        // extracted from the old default, never edited, and the default moved on: refresh it
        assertEquals(Bundled.REFRESH, ShaderType.decide("old", "old", "new"))
    }

    @Test
    fun testEditedCopy() {
        // no record, or a record of another default: keep the copy and warn once
        assertEquals(Bundled.KEEP_AND_WARN, ShaderType.decide(null, "mine", "new"))
        assertEquals(Bundled.KEEP_AND_WARN, ShaderType.decide("older", "mine", "new"))
    }
}
