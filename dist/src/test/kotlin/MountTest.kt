import kr.toxicity.hud.manager.MountManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MountTest {

    @Test
    fun testMount() {
        MountManager.clear()
        val a = File("build/tmp/mount-a")
        val b = File("build/tmp/mount-b")

        assertTrue(MountManager.mount(a))
        assertTrue(MountManager.mount(b))
        assertFalse(MountManager.mount(a), "mounting the same folder twice must be a no-op")
        assertEquals(listOf(a.absoluteFile, b.absoluteFile), MountManager.mountedFolders, "insertion order must be kept")

        // 路径统一存绝对路径，所以相对路径也能命中同一个挂载点
        assertTrue(MountManager.unmount(File(a.path)), "relative path must resolve to the same mounted folder")
        assertFalse(MountManager.unmount(a), "unmounting twice must be a no-op")
        assertEquals(listOf(b.absoluteFile), MountManager.mountedFolders)

        MountManager.clear()
        assertEquals(emptyList(), MountManager.mountedFolders)
    }
}
