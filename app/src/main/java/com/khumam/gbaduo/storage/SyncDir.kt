package com.khumam.gbaduo.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A file or folder in a [SyncDir], as last seen: size in bytes, modification time in epoch millis. */
data class SyncEntry(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * The few folder operations [SaveSync] needs, so the same sync logic runs on private
 * storage ([FileSyncDir]), on a folder picked with the system picker (DocumentSyncDir)
 * and in JVM tests. Names are plain file names inside this folder, never paths.
 */
interface SyncDir {
    fun entries(): List<SyncEntry>
    fun entry(name: String): SyncEntry? = entries().firstOrNull { it.name == name }

    /** The child folder [name]; created when missing if [create], else null. */
    fun subdir(name: String, create: Boolean): SyncDir?

    fun openRead(name: String): InputStream

    /** Creates [name] (or truncates it) for writing. */
    fun openWrite(name: String): OutputStream

    /** Renames [from] to [to]; [to] must not exist. */
    fun rename(from: String, to: String): Boolean

    fun delete(name: String): Boolean
}

/** [SyncDir] over a java.io folder: private storage, and the JVM tests. */
class FileSyncDir(val dir: File) : SyncDir {

    override fun entries(): List<SyncEntry> =
        dir.listFiles()?.map { SyncEntry(it.name, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
            ?: emptyList()

    override fun entry(name: String): SyncEntry? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return SyncEntry(name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified())
    }

    override fun subdir(name: String, create: Boolean): FileSyncDir? {
        val f = File(dir, name)
        if (f.isDirectory) return FileSyncDir(f)
        if (!create) return null
        return if (f.mkdirs() || f.isDirectory) FileSyncDir(f) else null
    }

    override fun openRead(name: String): InputStream = FileInputStream(File(dir, name))

    override fun openWrite(name: String): OutputStream {
        dir.mkdirs()
        return FileOutputStream(File(dir, name))
    }

    override fun rename(from: String, to: String): Boolean {
        val target = File(dir, to)
        if (target.exists()) return false
        return File(dir, from).renameTo(target)
    }

    override fun delete(name: String): Boolean = File(dir, name).let { !it.exists() || it.delete() }
}
