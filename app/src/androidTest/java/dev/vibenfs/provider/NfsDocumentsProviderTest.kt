package dev.vibenfs.provider

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.vibenfs.data.Volume
import dev.vibenfs.mount.MountManager.Companion.AUTHORITY
import dev.vibenfs.mounts
import dev.vibenfs.nfs.NfsVersion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the provider through ContentResolver like the Files app does. Needs the host server from NfsClientTest. */
@RunWith(AndroidJUnit4::class)
class NfsDocumentsProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val volume = Volume(name = "Test", host = "10.0.2.2", export = "/", version = NfsVersion.V3, port = 20490, mounted = true)

    @Before
    fun mount() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        context.mounts.save(volume)
    }

    @After
    fun cleanup() = runBlocking { context.mounts.delete(volume.id) }

    @Test
    fun browseCreateReadDelete() {
        val roots = column(DocumentsContract.buildRootsUri(AUTHORITY), Root.COLUMN_ROOT_ID)
        assertTrue(volume.id in roots)

        val rootDoc = "${volume.id}:/"
        val name = "provider-${System.nanoTime()}.txt"
        val created = DocumentsContract.createDocument(
            resolver, DocumentsContract.buildDocumentUri(AUTHORITY, rootDoc), "text/plain", name,
        )!!
        resolver.openOutputStream(created, "wt")!!.use { it.write("hello vibe".toByteArray()) }
        assertEquals("hello vibe", resolver.openInputStream(created)!!.use { it.readBytes().decodeToString() })

        // Second create with the same name must not clobber the first.
        val twin = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUri(AUTHORITY, rootDoc), "text/plain", name)!!
        val children = column(DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDoc), Document.COLUMN_DISPLAY_NAME)
        assertTrue(name in children && name.replace(".txt", " (1).txt") in children)
        assertTrue(DocumentsContract.deleteDocument(resolver, twin))

        assertTrue(DocumentsContract.deleteDocument(resolver, created))
    }

    private fun column(uri: android.net.Uri, name: String) = resolver.query(uri, null, null, null, null)!!.use { c ->
        generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow(name)) else null }.toList()
    }
}
