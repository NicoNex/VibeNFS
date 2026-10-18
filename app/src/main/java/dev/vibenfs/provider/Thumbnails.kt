package dev.vibenfs.provider

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Point
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.CancellationSignal
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Thumbnails read straight from NFS: embedded previews first (EXIF, album art), then a decode at the
 * target size. Results, including misses, are cached on disk.
 */
object Thumbnails {
    private const val CACHE_BYTES = 64L * 1024 * 1024
    // A full decode pulls the whole file over the network into memory; past this, show the icon instead.
    private const val MAX_DECODE_BYTES = 64L * 1024 * 1024

    fun supports(mime: String) = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

    /**
     * Cached JPEG for [key], rendering it with [open] on a miss. Null when the file has no usable
     * picture; that answer is cached too, so audio without cover art is not re-read on every listing.
     */
    fun cached(dir: File, key: String, mime: String, size: Point, signal: CancellationSignal?, open: () -> BlockReader): File? {
        val name = UUID.nameUUIDFromBytes(key.toByteArray())
        val file = File(dir, "$name.jpg")
        val miss = File(dir, "$name.none")
        if (file.exists()) return file.also { it.setLastModified(System.currentTimeMillis()) }
        if (miss.exists()) return null
        dir.mkdirs()
        val bitmap = open().use { render(it, mime, size, signal) }
        if (bitmap == null) {
            miss.createNewFile()
            return null
        }
        // Unique temp per request: two concurrent requests for the same key never share a half-written file.
        val tmp = File(dir, "$name.${UUID.randomUUID()}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (!tmp.renameTo(file)) tmp.delete()
        trim(dir)
        return file.takeIf { it.exists() }
    }

    private fun render(reader: BlockReader, mime: String, size: Point, signal: CancellationSignal?): Bitmap? {
        signal?.throwIfCanceled()
        return try {
            when {
                mime.startsWith("video/") -> retriever(reader, signal).use {
                    it.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.x, size.y)
                }
                mime.startsWith("audio/") -> retriever(reader, signal).use {
                    it.embeddedPicture?.let { art -> decode(ImageDecoder.createSource(art), size) }
                }
                else -> exif(reader, signal, size) ?: run {
                    if (reader.size > MAX_DECODE_BYTES) return null
                    val bytes = reader.stream(signal).use { it.readBytes() }
                    decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), size)
                }
            }
        } catch (e: Exception) {
            if (e is android.os.OperationCanceledException) throw e
            null // unreadable or unsupported media: the caller shows the generic icon
        }
    }

    /** Random access for the media framework, served by [reader] in this process. */
    private fun retriever(reader: BlockReader, signal: CancellationSignal?) = MediaMetadataRetriever().apply {
        setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                signal?.throwIfCanceled()
                return if (position >= reader.size) -1 else synchronized(reader) { reader.read(position, buffer, offset, size) }
            }
            override fun getSize() = reader.size
            override fun close() = Unit
        })
    }

    /** Cameras embed a small JPEG preview in the header: no need to pull the full photo over the network. */
    private fun exif(reader: BlockReader, signal: CancellationSignal?, size: Point): Bitmap? = try {
        val exif = reader.stream(signal).use { ExifInterface(it) }
        exif.thumbnailBitmap?.takeIf { it.width >= size.x / 2 }?.let { thumb ->
            // Unlike ImageDecoder, the embedded preview is stored unrotated.
            val degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) thumb
            else Bitmap.createBitmap(thumb, 0, 0, thumb.width, thumb.height, Matrix().apply { postRotate(degrees) }, true)
        }
    } catch (e: Exception) {
        if (e is android.os.OperationCanceledException) throw e
        null // broken EXIF: fall back to a full decode
    }

    /** ImageDecoder handles JPEG, PNG, WebP, HEIF and AVIF, applies EXIF rotation and decodes at the requested size. */
    private fun decode(source: ImageDecoder.Source, size: Point): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = maxOf(size.x.toFloat() / info.size.width, size.y.toFloat() / info.size.height).coerceAtMost(1f)
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // hardware bitmaps can't be compressed to the cache
        }

    /** Least recently used first, until the cache fits [CACHE_BYTES]. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
