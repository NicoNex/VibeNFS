package dev.vibenfs.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import dev.vibenfs.nfs.NfsVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A configured NFS export. [mounted] is the user's intent and survives process death. */
@Serializable
data class Volume(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val export: String,
    val version: NfsVersion = NfsVersion.AUTO,
    val port: Int = 0,
    val uid: Int = -1,
    val gid: Int = -1,
    val readOnly: Boolean = false,
    /** Off for slow links or shares without media: Files then shows plain icons and reads nothing extra. */
    val thumbnails: Boolean = true,
    val mounted: Boolean = false,
)

private val json = Json { ignoreUnknownKeys = true }

private object VolumesSerializer : Serializer<List<Volume>> {
    override val defaultValue = emptyList<Volume>()
    override suspend fun readFrom(input: InputStream): List<Volume> = try {
        json.decodeFromString(input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable volumes.json", e)
    }
    override suspend fun writeTo(t: List<Volume>, output: OutputStream) = output.write(json.encodeToString(t).encodeToByteArray())
}

// ponytail: a corrupt file resets to no volumes; the user re-adds them rather than the app crash-looping.
val Context.volumeStore: DataStore<List<Volume>> by dataStore(
    "volumes.json", VolumesSerializer, corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
)
