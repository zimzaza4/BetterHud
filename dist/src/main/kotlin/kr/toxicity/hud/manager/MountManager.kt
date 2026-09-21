package kr.toxicity.hud.manager

import java.io.File

/**
 * Registry of mounted (external) config folders.
 *
 * BetterHud's own data folder is always loaded first, so a mounted folder can only add
 * definitions, never override a same-named one (`putIfAbsent` semantics).
 * Mounted folders are reloaded together with BetterHud and dropped when BetterHud is disabled.
 */
object MountManager {

    private val folders = LinkedHashSet<File>()

    val mountedFolders: List<File>
        get() = synchronized(folders) { folders.toList() }

    fun mount(folder: File): Boolean = synchronized(folders) { folders.add(folder.absoluteFile) }

    fun unmount(folder: File): Boolean = synchronized(folders) { folders.remove(folder.absoluteFile) }

    fun clear() = synchronized(folders) { folders.clear() }
}
