package dev.vibenfs.mount

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import android.provider.DocumentsContract
import dev.vibenfs.R
import dev.vibenfs.data.Volume
import dev.vibenfs.data.volumeStore
import dev.vibenfs.nfs.NfsClient
import dev.vibenfs.widget.FoldersWidget
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface MountState {
    data object Off : MountState
    data object Connecting : MountState
    /** Space is null when the server does not answer FSSTAT. */
    data class Mounted(val totalBytes: Long?, val freeBytes: Long?) : MountState
    data class Error(val message: String) : MountState
}

/**
 * Owns live NFS connections. "Mounted" is the persisted [Volume.mounted] flag; the connection
 * itself is opened lazily and reopened after process death the first time someone needs it.
 */
class MountManager(private val context: Context) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w("MountManager", e) },
    )
    private val store = context.volumeStore
    private val clients = ConcurrentHashMap<String, NfsClient>()
    // Per-volume, so an unreachable server never blocks connects to the others. Cache hits and I/O take no lock.
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }

    /** Null until DataStore has loaded, so the UI never flashes the empty state. */
    val volumes: StateFlow<List<Volume>?> = store.data
        .catch { Log.w("MountManager", it); emit(emptyList()) } // an unreadable store must not hold the splash forever
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _states = MutableStateFlow<Map<String, MountState>>(emptyMap())
    val states: StateFlow<Map<String, MountState>> = _states.asStateFlow()

    init {
        // Home screen widgets render outside the app: push every state change to them.
        scope.launch {
            combine(volumes.filterNotNull(), states) { _, _ -> }.debounce(250)
                .collect { runCatching { FoldersWidget.refresh(context) } } // one failed update must not stop the rest
        }
        // A new default network (Wi-Fi <-> mobile) strands TCP sessions on the old one. libnfs only
        // notices after RPC timeouts, so move every mounted folder over right away. The first
        // callback is the network we start on: nothing to move yet.
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null
                override fun onAvailable(network: Network) {
                    val previous = current
                    current = network
                    if (previous != null && previous != network) scope.launch { reconnectAll() }
                }
            },
        )
        // Restore connections the user left mounted before the process died.
        scope.launch {
            store.data.first().filter { it.mounted }.forEach { launch { runCatching { connect(it.id) }; notifyChanged() } }
        }
    }

    /** Inserts or replaces [volume]; a mounted volume reconnects with the new settings. */
    suspend fun save(volume: Volume) {
        store.updateData { list -> list.filterNot { it.id == volume.id } + volume }
        disconnect(volume.id)
        if (volume.mounted) runCatching { connect(volume.id) }
        notifyChanged()
    }

    suspend fun delete(id: String) {
        disconnect(id)
        store.updateData { list -> list.filterNot { it.id == id } }
        notifyChanged()
    }

    suspend fun setMounted(id: String, mounted: Boolean) {
        store.updateData { list -> list.map { if (it.id == id) it.copy(mounted = mounted) else it } }
        if (mounted) runCatching { connect(id) } else disconnect(id)
        notifyChanged()
    }

    /** One store write and one notification for the whole batch; connects run in parallel. */
    suspend fun setAllMounted(mounted: Boolean) {
        val all = store.updateData { list -> list.map { it.copy(mounted = mounted) } }
        coroutineScope { all.forEach { launch { if (mounted) runCatching { connect(it.id) } else disconnect(it.id) } } }
        notifyChanged()
    }

    fun saveInBackground(volume: Volume) = scope.launch { save(volume) }
    fun deleteInBackground(id: String) = scope.launch { delete(id) }
    fun mount(id: String, mounted: Boolean) = scope.launch { setMounted(id, mounted) }
    fun setAllInBackground(mounted: Boolean) = scope.launch { setAllMounted(mounted) }
    fun toggleAll() = scope.launch { setAllMounted(store.data.first().none { it.mounted }) }
    fun toggle(id: String) = scope.launch {
        store.data.first().firstOrNull { it.id == id }?.let { setMounted(id, !it.mounted) }
    }

    /** Current volumes, waiting for DataStore on a cold process (the provider can be the first caller). */
    fun volumesNow(): List<Volume> = runBlocking { store.data.first() }

    /**
     * Connected client for a mounted volume, connecting on first use. Blocks; call off the main thread.
     * ponytail: a dropped TCP session is healed by libnfs autoreconnect, not by evicting the client here.
     */
    fun client(id: String): NfsClient = clients[id] ?: runBlocking { connect(id) }

    private suspend fun connect(id: String): NfsClient = lock(id).withLock {
        clients[id]?.let { return it }
        val volume = store.data.first().firstOrNull { it.id == id && it.mounted }
            ?: throw IOException("Volume $id is not mounted")
        setState(id, MountState.Connecting)
        try {
            val client = withContext(Dispatchers.IO) {
                NfsClient.mount(volume.host, volume.export, volume.version, volume.port, volume.uid, volume.gid)
            }
            clients[id] = client
            refreshSpace(id, client)
            client
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("VibeNFS", "mount ${volume.host}:${volume.export} (${volume.version}) failed", e)
            setState(id, MountState.Error(e.message ?: context.getString(R.string.connection_error)))
            throw e as? IOException ?: IOException(e)
        }
    }

    /**
     * Fresh connections for every mounted folder, including ones in error that the new network may
     * now reach. Old clients close afterwards, off the lock: close() waits out their in-flight calls,
     * which fail within the RPC timeout, so nothing blocks for good.
     */
    private suspend fun reconnectAll() = coroutineScope {
        store.data.first().filter { it.mounted }.forEach { volume ->
            launch {
                val old = lock(volume.id).withLock { clients.remove(volume.id) }
                // Routes on a network that just came up can lag its callback: retry briefly.
                for (wait in RETRY_MS) {
                    delay(wait)
                    if (runCatching { connect(volume.id) }.isSuccess) break
                }
                old?.let { withContext(Dispatchers.IO) { it.close() } }
            }
        }
    }

    private suspend fun disconnect(id: String) = lock(id).withLock {
        clients.remove(id)?.let { withContext(Dispatchers.IO) { it.close() } }
        setState(id, MountState.Off)
    }

    private fun refreshSpace(id: String, client: NfsClient) {
        val space = runCatching { client.space() }.getOrNull()
        setState(id, MountState.Mounted(space?.first, space?.second))
    }

    private fun setState(id: String, state: MountState) = _states.update { it + (id to state) }

    private fun notifyChanged() {
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
    }

    companion object {
        const val AUTHORITY = "dev.vibenfs.documents"
        private val RETRY_MS = longArrayOf(0, 2_000, 5_000)
    }
}
