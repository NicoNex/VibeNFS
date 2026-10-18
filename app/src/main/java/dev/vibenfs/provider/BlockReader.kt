package dev.vibenfs.provider

import android.os.CancellationSignal
import dev.vibenfs.nfs.NfsClient
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Random access to one open NFS file. Callers ask for 4–128 KiB at a time and every NFS round trip
 * costs a full network latency (~65 ms over a VPN), so reads are served from whole blocks, and once
 * access turns sequential the next blocks are fetched in parallel, like kernel readahead: libnfs
 * pipelines them on one connection, so a streamed file costs bandwidth instead of round trips.
 *
 * One reader at a time (not thread-safe); [close] waits out in-flight fetches, then closes [fh].
 */
class BlockReader(private val nfs: NfsClient, private val fh: Long, var size: Long) : Closeable {
    private val blocks = object : LinkedHashMap<Long, Future<ByteArray>>(BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Future<ByteArray>>) = size > BLOCKS
    }
    private val issued = ArrayList<Future<ByteArray>>()
    private var lastBlock = -2L
    private var ahead = 0

    /** Reads up to [len] bytes at [pos] into [dst]; 0 at or past the end of the file. */
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        var done = 0
        while (done < len && pos + done < size) {
            val at = pos + done
            val index = at / BLOCK
            // Adaptive window, like kernel readahead: start with nothing in flight (a thumbnail
            // wants one header block), double on each sequential step, reset on a seek.
            if (index != lastBlock) {
                ahead = if (index == lastBlock + 1) (ahead * 2).coerceIn(1, MAX_AHEAD) else 0
                for (next in index + 1..index + ahead) fetch(next, prefetch = true)
            }
            lastBlock = index
            val block = try {
                fetch(index, prefetch = false)!!.get()
            } catch (e: ExecutionException) {
                blocks.remove(index)
                throw e.cause ?: e
            }
            // A short block is final only at the end of the file; anywhere else, refetch it next time.
            if (block.size < BLOCK && index * BLOCK + block.size < size) blocks.remove(index)
            val from = (at - index * BLOCK).toInt()
            if (from >= block.size) break // server returned less than expected: stop at what we have
            val n = minOf(len - done, block.size - from)
            System.arraycopy(block, from, dst, off + done, n)
            done += n
        }
        return done
    }

    /** Writes through to the server, dropping cached blocks first so later reads see the new bytes. */
    fun write(pos: Long, src: ByteArray, len: Int): Int {
        settle()
        blocks.clear() // ponytail: drops the whole cache, writers rarely read back
        return nfs.write(fh, src, len, pos).also { size = maxOf(size, pos + it) }
    }

    /** Sequential stream from the start of the file; checks [signal] on every read. */
    fun stream(signal: CancellationSignal?) = object : InputStream() {
        private var pos = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            signal?.throwIfCanceled()
            if (len == 0) return 0
            return this@BlockReader.read(pos, b, off, len).also { pos += it }.takeIf { it > 0 } ?: -1
        }
        override fun skip(n: Long): Long = n.coerceIn(0, size - pos).also { pos += it }
        override fun available() = (size - pos).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        settle()
        nfs.close(fh)
    }

    /** Cached or in-flight block; a prefetch is skipped (null) when every reader thread is busy, a demand read then runs here. */
    private fun fetch(index: Long, prefetch: Boolean): Future<ByteArray>? {
        blocks[index]?.let { return it }
        if (prefetch && index * BLOCK >= size) return null
        val task = FutureTask {
            val buf = ByteArray(BLOCK)
            val n = nfs.read(fh, buf, BLOCK, index * BLOCK)
            if (n == BLOCK) buf else buf.copyOf(n)
        }
        try {
            POOL.execute(task)
        } catch (_: RejectedExecutionException) {
            if (prefetch) return null
            task.run()
        }
        issued.removeAll { it.isDone }
        issued += task
        blocks[index] = task
        return task
    }

    /** Waits out in-flight fetches, evicted ones included: the handle must not change or close under them. */
    private fun settle() = issued.forEach { runCatching { it.get() } }

    companion object {
        const val BLOCK = 128 * 1024
        private const val MAX_AHEAD = 16 // 2 MiB in flight for a streaming reader
        private const val BLOCKS = MAX_AHEAD + 4 // cache per open file: 2.5 MiB at most
        // Shared by all open files; idle threads exit, so a quiet provider holds none.
        private val POOL = ThreadPoolExecutor(0, MAX_AHEAD, 30, TimeUnit.SECONDS, SynchronousQueue()) { Thread(it, "nfs-read").apply { isDaemon = true } }
    }
}
