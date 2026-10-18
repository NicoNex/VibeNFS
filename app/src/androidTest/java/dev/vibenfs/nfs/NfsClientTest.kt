package dev.vibenfs.nfs

import android.system.OsConstants.O_CREAT
import android.system.OsConstants.O_RDONLY
import android.system.OsConstants.O_RDWR
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trips against a local NFSv3 server reachable from the emulator, e.g.
 * `go run github.com/willscott/go-nfs/example/osnfs <dir> 20490` on the host.
 */
@RunWith(AndroidJUnit4::class)
class NfsClientTest {
    @Before
    fun grantLan() = InstrumentationRegistry.getInstrumentation().run {
        uiAutomation.grantRuntimePermission(targetContext.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
    }

    @Test
    fun mountListWriteReadDelete() = NfsClient.mount("10.0.2.2", "/", NfsVersion.V3, port = 20490).use { nfs ->
        val dir = "/vibe-test-${System.nanoTime()}"
        val payload = ByteArray(300_000) { it.toByte() }
        nfs.mkdir(dir)
        try {
            val fh = nfs.open("$dir/blob.bin", O_RDWR or O_CREAT)
            assertEquals(payload.size, nfs.write(fh, payload, payload.size, 0))
            nfs.close(fh)
            assertTrue(nfs.list(dir).single().let { it.name == "blob.bin" && it.size == payload.size.toLong() })
            val back = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            val rh = nfs.open("$dir/blob.bin", O_RDONLY)
            while (true) {
                val n = nfs.read(rh, chunk, chunk.size, back.size().toLong())
                if (n == 0) break
                back.write(chunk, 0, n)
            }
            nfs.close(rh)
            assertArrayEquals(payload, back.toByteArray())
        } finally {
            runCatching { nfs.delete("$dir/blob.bin", isDirectory = false) }
            nfs.delete(dir, isDirectory = true)
        }
        assertTrue(nfs.list("/").none { it.name == dir.removePrefix("/") })
    }
}
