package dev.vibenfs.nfs

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** File or directory as reported by the server. Built from JNI, keep the constructor in sync with nfs_jni.c. */
data class NfsEntry(val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

enum class NfsVersion(val wire: Int) { AUTO(0), V3(3), V4(4) }

/** One mounted export. Thread-safe: libnfs runs its own service thread per context. */
class NfsClient private constructor(private var handle: Long) : Closeable {
    // Calls share the read lock; close() takes the write lock so it never frees the context under a running call.
    private val lock = ReentrantReadWriteLock()

    private inline fun <T> call(block: (ctx: Long) -> T): T = lock.read {
        if (handle == 0L) throw IOException("NFS connection closed")
        block(handle)
    }

    fun list(path: String): List<NfsEntry> = call { nList(it, path).asList() }
    fun stat(path: String): NfsEntry = call { nStat(it, path) }
    /** Total and available bytes of the export. */
    fun space(): Pair<Long, Long> = call { nStatVfs(it) }.let { it[0] to it[1] }
    fun open(path: String, flags: Int): Long = call { nOpen(it, path, flags) }
    fun read(fh: Long, buf: ByteArray, size: Int, offset: Long): Int = call { nPread(it, fh, buf, size, offset) }
    fun write(fh: Long, buf: ByteArray, size: Int, offset: Long): Int = call { nPwrite(it, fh, buf, size, offset) }
    fun close(fh: Long) = call { nClose(it, fh) }
    fun mkdir(path: String) = call { nMkdir(it, path) }
    fun delete(path: String, isDirectory: Boolean) = call { nDelete(it, path, isDirectory) }
    fun rename(from: String, to: String) = call { nRename(it, from, to) }

    override fun close() = lock.write {
        if (handle != 0L) {
            nUmount(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("vibenfs")
        }

        /**
         * @param port 0 = discover via portmapper, otherwise NFS and MOUNT on this port.
         * @param uid/gid AUTH_SYS identity, -1 keeps libnfs' default. Throws IOException on failure.
         */
        fun mount(host: String, export: String, version: NfsVersion, port: Int = 0, uid: Int = -1, gid: Int = -1, timeoutMs: Int = 10_000): NfsClient {
            fun attempt(v: NfsVersion) = try {
                NfsClient(nMount(host, export, v.wire, port, true, uid, gid, timeoutMs))
            } catch (e: IOException) {
                // Servers without MOUNT EXPORT (common for userspace ones) can't do nested-export
                // traversal; mount them flat instead of failing.
                if (e.message.orEmpty().contains("Procedure not available")) {
                    NfsClient(nMount(host, export, v.wire, port, false, uid, gid, timeoutMs))
                } else throw e
            }
            if (version != NfsVersion.AUTO) return attempt(version)
            // AUTO prefers v4 (single port, firewall friendly) and falls back to v3,
            // except when the server is simply unreachable: then v3 would just time out again.
            return try {
                attempt(NfsVersion.V4)
            } catch (v4: IOException) {
                if (v4.message.orEmpty().contains(Regex("timed out|unreachable", RegexOption.IGNORE_CASE))) throw v4
                try {
                    attempt(NfsVersion.V3)
                } catch (v3: IOException) {
                    throw v3.apply { addSuppressed(v4) }
                }
            }
        }

        /** Exports advertised by the server's MOUNT service (NFSv3); empty if none or unreachable. */
        fun exports(host: String, port: Int = 0): List<String> = nExports(host, port).asList()

        @JvmStatic private external fun nMount(host: String, export: String, version: Int, port: Int, traverse: Boolean, uid: Int, gid: Int, timeoutMs: Int): Long
        @JvmStatic private external fun nUmount(ctx: Long)
        @JvmStatic private external fun nExports(host: String, port: Int): Array<String>
        @JvmStatic private external fun nList(ctx: Long, path: String): Array<NfsEntry>
        @JvmStatic private external fun nStat(ctx: Long, path: String): NfsEntry
        @JvmStatic private external fun nStatVfs(ctx: Long): LongArray
        @JvmStatic private external fun nOpen(ctx: Long, path: String, flags: Int): Long
        @JvmStatic private external fun nPread(ctx: Long, fh: Long, buf: ByteArray, size: Int, offset: Long): Int
        @JvmStatic private external fun nPwrite(ctx: Long, fh: Long, buf: ByteArray, size: Int, offset: Long): Int
        @JvmStatic private external fun nClose(ctx: Long, fh: Long)
        @JvmStatic private external fun nMkdir(ctx: Long, path: String)
        @JvmStatic private external fun nDelete(ctx: Long, path: String, isDir: Boolean)
        @JvmStatic private external fun nRename(ctx: Long, from: String, to: String)
    }
}
