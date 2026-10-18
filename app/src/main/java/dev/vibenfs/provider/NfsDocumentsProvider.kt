package dev.vibenfs.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Point
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import dev.vibenfs.R
import dev.vibenfs.mount.MountManager
import dev.vibenfs.mount.MountManager.Companion.AUTHORITY
import dev.vibenfs.mount.MountState
import dev.vibenfs.mounts
import dev.vibenfs.nfs.NfsClient
import dev.vibenfs.nfs.NfsEntry
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Exposes every mounted volume as a root in the system Files app. Document ids are `volumeId:/path`. */
class NfsDocumentsProvider : DocumentsProvider() {

    private val mounts: MountManager get() = context!!.mounts

    override fun onCreate() = true

    override fun queryRoots(projection: Array<String>?) = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        val states = mounts.states.value
        mounts.volumesNow().filter { it.mounted }.forEach { v ->
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, v.id)
                add(Root.COLUMN_DOCUMENT_ID, DocId(v.id, "/").toString())
                add(Root.COLUMN_TITLE, v.name)
                add(Root.COLUMN_SUMMARY, "${v.host}:${v.export}")
                add(Root.COLUMN_ICON, R.drawable.ic_root_nfs)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or if (v.readOnly) 0 else Root.FLAG_SUPPORTS_CREATE)
                (states[v.id] as? MountState.Mounted)?.freeBytes?.let { add(Root.COLUMN_AVAILABLE_BYTES, it) }
            }
        }
        setNotificationUri(context!!.contentResolver, DocumentsContract.buildRootsUri(AUTHORITY))
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor = io {
        val doc = DocId.parse(documentId)
        val entry = client(doc).stat(doc.path).copy(name = doc.name)
        MatrixCursor(projection ?: DOC_COLUMNS).also { it.addEntry(doc, entry, writable(doc)) }
    }

    override fun queryChildDocuments(parentId: String, projection: Array<String>?, sortOrder: String?): Cursor = io {
        val parent = DocId.parse(parentId)
        MatrixCursor(projection ?: DOC_COLUMNS).apply {
            val writable = writable(parent)
            client(parent).list(parent.path).forEach { addEntry(parent.child(it.name), it, writable) }
            setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentId))
        }
    }

    override fun isChildDocument(parentId: String, documentId: String): Boolean {
        val parent = DocId.parse(parentId)
        val doc = DocId.parse(documentId)
        return parent.volume == doc.volume && (parent.path == "/" || doc.path.startsWith(parent.path + "/"))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = io {
        val doc = DocId.parse(documentId)
        val nfs = client(doc)
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        if (pfdMode and ParcelFileDescriptor.MODE_READ_ONLY == 0) requireWritable(doc)
        val flags = when {
            pfdMode and ParcelFileDescriptor.MODE_READ_WRITE == ParcelFileDescriptor.MODE_READ_WRITE -> OsConstants.O_RDWR
            pfdMode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0 -> OsConstants.O_WRONLY
            else -> OsConstants.O_RDONLY
        } or (if (pfdMode and ParcelFileDescriptor.MODE_TRUNCATE != 0) OsConstants.O_TRUNC else 0)
        val size = if (flags and OsConstants.O_TRUNC != 0) 0L else nfs.stat(doc.path).size
        val fh = nfs.open(doc.path, flags)
        // One thread per open file so parallel reads (e.g. video + thumbnailer) don't queue behind each other.
        val thread = HandlerThread("nfs-io").apply { start() }
        try {
            context!!.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                pfdMode, ProxyCallback(BlockReader(nfs, fh, size), thread), Handler(thread.looper),
            )
        } catch (e: Exception) {
            runCatching { nfs.close(fh) }
            thread.quitSafely()
            throw e
        }
    }

    override fun createDocument(parentId: String, mimeType: String, displayName: String): String = io {
        val parent = DocId.parse(parentId)
        requireWritable(parent)
        requireName(displayName)
        val nfs = client(parent)
        // Exclusive create on "name", "name (1)", ... so a collision costs one round trip, not a directory listing.
        val doc = (0..999).asSequence().map { parent.child(candidateName(displayName, it)) }.first { doc ->
            try {
                if (mimeType == Document.MIME_TYPE_DIR) nfs.mkdir(doc.path)
                else nfs.close(nfs.open(doc.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL))
                true
            } catch (_: FileAlreadyExistsException) {
                false
            }
        }
        notifyChildren(parent)
        doc.toString()
    }

    override fun deleteDocument(documentId: String) = io {
        val doc = DocId.parse(documentId)
        requireWritable(doc)
        val nfs = client(doc)
        deleteRecursive(nfs, doc.path, nfs.stat(doc.path).isDirectory)
        notifyChildren(doc.parent())
    }

    override fun renameDocument(documentId: String, displayName: String): String = io {
        val doc = DocId.parse(documentId)
        requireWritable(doc)
        requireName(displayName)
        val renamed = doc.parent().child(displayName)
        val nfs = client(doc)
        requireAbsent(nfs, renamed)
        nfs.rename(doc.path, renamed.path)
        notifyChildren(doc.parent())
        renamed.toString()
    }

    override fun moveDocument(sourceId: String, sourceParentId: String, targetParentId: String): String = io {
        val doc = DocId.parse(sourceId)
        val target = DocId.parse(targetParentId)
        if (doc.volume != target.volume) throw UnsupportedOperationException("Cross-volume move")
        requireWritable(doc)
        val moved = target.child(doc.name)
        val nfs = client(doc)
        requireAbsent(nfs, moved)
        nfs.rename(doc.path, moved.path)
        notifyChildren(doc.parent())
        notifyChildren(target)
        moved.toString()
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor = io {
        val doc = DocId.parse(documentId)
        val entry = client(doc).stat(doc.path)
        // Keyed by content identity, so an edited file gets a fresh thumbnail.
        val key = "$documentId|${entry.size}|${entry.lastModified}|${sizeHint.x}"
        // Read straight over NFS, never through our own proxy fd: a thread of this process blocked on
        // a FUSE file this process serves can't be killed and leaves a zombie until reboot.
        val nfs = client(doc)
        val file = Thumbnails.cached(File(context!!.cacheDir, "thumbs"), key, mimeOf(entry.name), sizeHint, signal) {
            BlockReader(nfs, nfs.open(doc.path, OsConstants.O_RDONLY), entry.size)
        } ?: throw FileNotFoundException("No thumbnail for ${doc.name}")
        AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
    }

    private fun client(doc: DocId): NfsClient = mounts.client(doc.volume)

    private fun deleteRecursive(nfs: NfsClient, path: String, isDirectory: Boolean) {
        if (isDirectory) nfs.list(path).forEach { deleteRecursive(nfs, "$path/${it.name}", it.isDirectory) }
        nfs.delete(path, isDirectory)
    }

    private fun notifyChildren(parent: DocId) =
        context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, parent.toString()), null)

    private fun writable(doc: DocId) = mounts.volumesNow().firstOrNull { it.id == doc.volume }?.readOnly == false

    private fun requireWritable(doc: DocId) {
        if (!writable(doc)) throw FileNotFoundException("Volume is read only")
    }

    /** Display names come from other apps: refuse anything that would escape the parent folder. */
    private fun requireName(name: String) {
        if (name.isEmpty() || '/' in name || name == "." || name == "..") throw FileNotFoundException("Invalid name: $name")
    }

    /** NFS RENAME silently replaces an existing target, so refuse instead of losing the other file. */
    private fun requireAbsent(nfs: NfsClient, doc: DocId) {
        val exists = try {
            nfs.stat(doc.path); true
        } catch (_: FileNotFoundException) {
            false
        }
        if (exists) throw FileNotFoundException("${doc.name} already exists")
    }

    private fun MatrixCursor.addEntry(doc: DocId, entry: NfsEntry, writable: Boolean) {
        val mime = if (entry.isDirectory) Document.MIME_TYPE_DIR else mimeOf(entry.name)
        val thumb = if (Thumbnails.supports(mime)) Document.FLAG_SUPPORTS_THUMBNAIL else 0
        val flags = thumb or if (!writable) 0 else
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE or
                if (entry.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, doc.toString())
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, entry.size)
            add(Document.COLUMN_LAST_MODIFIED, entry.lastModified)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    /**
     * Callbacks run on the per-file [thread]; errors surface to the reader as EIO. A volume unmounted
     * mid-read closes [nfs], which then fails calls cleanly instead of touching freed native state.
     */
    private class ProxyCallback(private val reader: BlockReader, private val thread: HandlerThread) : ProxyFileDescriptorCallback() {
        override fun onGetSize() = reader.size
        override fun onRead(offset: Long, size: Int, data: ByteArray) = errno { reader.read(offset, data, 0, size) }
        override fun onWrite(offset: Long, size: Int, data: ByteArray) = errno { reader.write(offset, data, size) }
        override fun onFsync() = Unit
        override fun onRelease() {
            runCatching { reader.close() }
            thread.quitSafely()
        }

        private inline fun <T> errno(block: () -> T): T = try {
            block()
        } catch (e: Exception) {
            throw ErrnoException(e.message, OsConstants.EIO)
        }
    }

    companion object {

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        private fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"

        /** Attempt [n] at a free name: "a.txt", "a (1).txt", ... like the system providers. */
        fun candidateName(name: String, n: Int): String {
            if (n == 0) return name
            val dot = name.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
            return "$stem ($n)$ext"
        }

        /** SAF wants FileNotFoundException for anything that goes wrong reaching the file. */
        private inline fun <T> io(block: () -> T): T = try {
            block()
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw FileNotFoundException(e.message).apply { initCause(e) }
        }
    }
}

/** `volumeId:/abs/path` */
data class DocId(val volume: String, val path: String) {
    val name: String get() = if (path == "/") "/" else path.substringAfterLast('/')
    fun child(name: String) = DocId(volume, if (path == "/") "/$name" else "$path/$name")
    fun parent() = DocId(volume, path.substringBeforeLast('/').ifEmpty { "/" })
    override fun toString() = "$volume:$path"

    companion object {
        fun parse(id: String) = DocId(id.substringBefore(':'), id.substringAfter(':'))
    }
}
