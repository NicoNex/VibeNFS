package dev.vibenfs.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.vibenfs.R
import dev.vibenfs.mounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Tap mounts or unmounts everything; long-press opens [VolumesSheetActivity] for single folders. */
class NfsTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        job?.cancel()
        // Reflects the user's mount intent; per-folder errors live in the long-press sheet.
        job = scope.launch { mounts.volumes.filterNotNull().collect { render(it.count { v -> v.mounted }, it.size) } }
    }

    override fun onStopListening() {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        mounts.toggleAll()
    }

    private fun render(mounted: Int, total: Int) = qsTile?.run {
        state = when {
            total == 0 -> Tile.STATE_UNAVAILABLE
            mounted > 0 -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        label = getString(R.string.tile_label)
        subtitle = if (mounted == 0) getString(R.string.tile_subtitle_none)
        else resources.getQuantityString(R.plurals.tile_subtitle_mounted, mounted, mounted)
        icon = Icon.createWithResource(this@NfsTileService, R.drawable.ic_tile_nfs)
        updateTile()
    }
}
