@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.vibenfs.tile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vibenfs.MainActivity
import dev.vibenfs.R
import dev.vibenfs.data.Volume
import dev.vibenfs.mount.MountState
import dev.vibenfs.mounts
import dev.vibenfs.ui.FolderBadge
import dev.vibenfs.ui.MonoLabel
import dev.vibenfs.ui.ErrorText
import dev.vibenfs.ui.PosterTitle
import dev.vibenfs.ui.StatusPill
import dev.vibenfs.ui.theme.VibeTheme

/** Long-press target of the Quick Settings tile, like the Wi-Fi and Bluetooth detail panels. */
class VolumesSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VibeTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val scope = rememberCoroutineScope()
                // Let the sheet animate out before the transparent host goes away.
                val dismiss: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { finish() } }
                ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
                    VolumesSheet(onOpenApp = {
                        startActivity(Intent(this@VolumesSheetActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    })
                }
            }
        }
    }
}

@Composable
private fun VolumesSheet(onOpenApp: () -> Unit) {
    val mounts = androidx.compose.ui.platform.LocalContext.current.mounts
    val volumes = mounts.volumes.collectAsStateWithLifecycle().value ?: return
    val states by mounts.states.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            PosterTitle(stringResource(R.string.folders_poster), Modifier.weight(1f), size = 64, color = colors.primary)
            StatusPill(stringResource(R.string.mounted_ratio, volumes.count { it.mounted }, volumes.size), colors.tertiaryContainer, colors.onTertiaryContainer)
        }
        volumes.forEach { v -> SheetRow(v, states[v.id] ?: if (v.mounted) MountState.Connecting else MountState.Off) { mounts.mount(v.id, it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.padding(top = 4.dp)) {
            Button(
                onClick = { mounts.setAllInBackground(true) },
                shape = RoundedCornerShape(28.dp, 8.dp, 8.dp, 28.dp),
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(stringResource(R.string.mount_all)) }
            Button(
                onClick = { mounts.setAllInBackground(false) },
                shape = RoundedCornerShape(8.dp, 28.dp, 28.dp, 8.dp),
                colors = ButtonDefaults.buttonColors(colors.secondaryContainer, colors.onSecondaryContainer),
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(stringResource(R.string.unmount_all)) }
        }
        TextButton(onClick = onOpenApp, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.open_app)) }
    }
}

@Composable
private fun SheetRow(volume: Volume, state: MountState, onMount: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        is MountState.Error -> colors.errorContainer to colors.onErrorContainer
        is MountState.Mounted, MountState.Connecting -> colors.primaryContainer to colors.onPrimaryContainer
        MountState.Off -> colors.surfaceContainerHighest to colors.onSurface
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                MountState.Connecting -> LoadingIndicator(Modifier.size(40.dp), color = colors.primary)
                else -> FolderBadge(
                    (if (state is MountState.Mounted) MaterialShapes.Cookie9Sided else MaterialShapes.Square).toShape(),
                    if (state is MountState.Error) colors.error else if (state is MountState.Mounted) colors.primary else colors.outlineVariant,
                    if (state is MountState.Error) colors.onError else if (state is MountState.Mounted) colors.onPrimary else colors.onSurface,
                    40,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(volume.name, style = MaterialTheme.typography.titleMedium)
                if (state is MountState.Error) ErrorText(state.message, content, size = 11)
                else MonoLabel(volume.host, color = content, size = 11)
            }
            if (state is MountState.Error) Button(
                onClick = { onMount(true) },
                colors = ButtonDefaults.buttonColors(colors.error, colors.onError),
            ) { Text(stringResource(R.string.retry)) }
            else {
                val label = stringResource(if (volume.mounted) R.string.a11y_unmount else R.string.a11y_mount, volume.name)
                Switch(checked = volume.mounted, onCheckedChange = onMount, modifier = Modifier.semantics { contentDescription = label })
            }
        }
    }
}
