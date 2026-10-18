package dev.vibenfs.provider

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Point
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID

/**
 * Thumbnails rendered from a seekable NFS file descriptor: embedded previews first (EXIF, album
 * art), then a decode straight at the target size. Results, including misses, are cached on disk.
 */
object Thumbnails {
    private const val CACHE_BYTES = 64L * 1024 * 1024

    fun supports(mime: String) = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

    /**
     * Cached JPEG for [key], rendering it with [open] on a miss. Null when the file has no usable
     * picture; that answer is cached too, so audio without cover art is not re-read on every listing.
     */
    fun cached(dir: File, key: String, mime: String, size: Point, signal: CancellationSignal?, open: () -> ParcelFileDescriptor): File? {
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

    private fun render(pfd: ParcelFileDescriptor, mime: String, size: Point, signal: CancellationSignal?): Bitmap? {
        signal?.throwIfCanceled()
        return try {
            when {
                mime.startsWith("video/") -> MediaMetadataRetriever().use {
                    it.setDataSource(pfd.fileDescriptor)
                    it.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.x, size.y)
                }
                mime.startsWith("audio/") -> MediaMetadataRetriever().use {
                    it.setDataSource(pfd.fileDescriptor)
                    it.embeddedPicture?.let { art -> decode(ImageDecoder.createSource(art), size) }
                }
                else -> exif(pfd, size) ?: run {
                    signal?.throwIfCanceled()
                    decode(ImageDecoder.createSource { AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }, size)
                }
            }
        } catch (e: Exception) {
            if (e is android.os.OperationCanceledException) throw e
            null // unreadable or unsupported media: the caller shows the generic icon
        }
    }

    /** Cameras embed a small JPEG preview in the header: no need to pull the full photo over the network. */
    private fun exif(pfd: ParcelFileDescriptor, size: Point): Bitmap? = try {
        val exif = ExifInterface(pfd.fileDescriptor)
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
