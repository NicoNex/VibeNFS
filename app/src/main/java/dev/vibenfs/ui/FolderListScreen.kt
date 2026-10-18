@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.vibenfs.ui

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vibenfs.R
import dev.vibenfs.data.Volume
import dev.vibenfs.mount.MountManager
import dev.vibenfs.mount.MountState
import dev.vibenfs.mounts

@Composable
fun FolderListScreen(onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val mounts = LocalContext.current.mounts
    val volumes = mounts.volumes.collectAsStateWithLifecycle().value ?: return
    val states by mounts.states.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { LocalNetworkBanner() },
        floatingActionButton = {
            if (volumes.isNotEmpty()) MediumExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.add_folder)) },
                icon = { Icon(Icons.Rounded.Add, null) },
                onClick = onAdd,
                expanded = !listState.canScrollBackward,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        },
    ) { padding ->
        if (volumes.isEmpty()) {
            EmptyState(onAdd, padding)
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().widthIn(max = 840.dp),
        ) {
            item { Header(volumes, states) }
            items(volumes, key = { it.id }) { volume ->
                val state = states[volume.id] ?: if (volume.mounted) MountState.Connecting else MountState.Off
                FolderCard(volume, state, onClick = { onEdit(volume.id) }, onMount = { mounts.mount(volume.id, it) }, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun Header(volumes: List<Volume>, states: Map<String, MountState>) {
    val mounted = states.values.filterIsInstance<MountState.Mounted>()
    // Exports of one disk report the same numbers: count each disk once. Saturating, since some
    // servers report near Long.MAX_VALUE and a plain sum wraps negative.
    val free = mounted.filter { it.freeBytes != null }.distinctBy { it.totalBytes to it.freeBytes }
        .map { it.freeBytes!! }.takeIf { it.isNotEmpty() }
        ?.fold(0L) { sum, b -> if (sum > Long.MAX_VALUE - b) Long.MAX_VALUE else sum + b }
    Column(Modifier.padding(bottom = 8.dp)) {
        MonoLabel("VibeNFS", size = 13)
        PosterTitle(stringResource(R.string.folders_poster))
        MonoLabel(
            stringResource(R.string.stats_line, volumes.size, mounted.size) + (free?.let { " · " + stringResource(R.string.free_short, bytes(it)) } ?: ""),
            size = 13,
        )
    }
}

@Composable
private fun FolderCard(volume: Volume, state: MountState, onClick: () -> Unit, onMount: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content, shape) = when (state) {
        is MountState.Mounted -> Triple(colors.primary, colors.onPrimary, RoundedCornerShape(32.dp))
        MountState.Connecting -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, RoundedCornerShape(32.dp, 32.dp, 32.dp, 8.dp))
        is MountState.Error -> Triple(colors.errorContainer, colors.onErrorContainer, RoundedCornerShape(32.dp, 8.dp, 32.dp, 32.dp))
        MountState.Off -> Triple(colors.surfaceContainerLowest, colors.onSurface, RoundedCornerShape(8.dp, 32.dp, 32.dp, 32.dp))
    }
    Surface(onClick = onClick, color = container, contentColor = content, shape = shape, modifier = modifier.fillMaxWidth().animateContentSize()) {
        Box {
            if (state is MountState.Mounted) SpinningShape(colors.primaryContainer.copy(alpha = 0.25f), Modifier.align(Alignment.TopEnd).offset(46.dp, (-48).dp))
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is MountState.Mounted -> MountedBody(volume, state) { onMount(false) }
                    MountState.Connecting -> ConnectingBody(volume) { onMount(false) }
                    is MountState.Error -> ErrorBody(volume, state, onMount)
                    MountState.Off -> OffBody(volume) { onMount(true) }
                }
            }
        }
    }
}

@Composable
private fun MountedBody(volume: Volume, state: MountState.Mounted, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(stringResource(R.string.state_mounted), colors.tertiaryContainer, colors.onTertiaryContainer)
        Spacer(Modifier.weight(1f))
        MountSwitch(volume, true, onToggle, onPrimary = true)
    }
    Poster(volume.name, colors.primaryContainer, maxSize = 76)
    val total = state.totalBytes
    val free = state.freeBytes
    if (total != null && free != null && total > 0) {
        LinearWavyProgressIndicator(
            progress = { 1f - free.toFloat() / total },
            color = colors.onPrimary,
            // A tint of the wave, not primaryContainer: in dark theme that is as dark as the wave itself.
            trackColor = colors.onPrimary.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth().preferredFrameRate(DECOR_FPS),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (free != null) MonoStat(bytes(free), stringResource(R.string.label_free), colors.onPrimary, Modifier.weight(1f))
        if (total != null) MonoStat(bytes(total), stringResource(R.string.label_total), colors.onPrimary, Modifier.weight(1f))
        MonoStat(volume.version.label(), "NFS", colors.onPrimary, Modifier.weight(1f))
    }
    val context = LocalContext.current
    Button(
        onClick = {
            // Opens this root directly in the system Files app.
            val root = DocumentsContract.buildRootUri(MountManager.AUTHORITY, volume.id)
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM)) }
        },
        colors = ButtonDefaults.buttonColors(colors.primaryContainer, colors.onPrimaryContainer),
    ) { Text(stringResource(R.string.open_in_files)) }
}

@Composable
private fun ConnectingBody(volume: Volume, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LoadingIndicator(Modifier.size(54.dp), color = colors.onTertiaryContainer)
        Column(Modifier.weight(1f)) {
            Poster(volume.name, colors.onTertiaryContainer, maxSize = 48)
            MonoLabel("${stringResource(R.string.state_connecting)}… · ${volume.host}", color = colors.onTertiaryContainer)
        }
        FilledIconButton(
            onClick = onCancel,
            colors = IconButtonDefaults.filledIconButtonColors(colors.onTertiaryContainer, colors.tertiaryContainer),
            modifier = Modifier.size(48.dp),
        ) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
    }
}

@Composable
private fun ErrorBody(volume: Volume, state: MountState.Error, onMount: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Poster(volume.name, colors.onErrorContainer, maxSize = 48)
    ErrorText(state.message, colors.onErrorContainer)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onMount(true) }, colors = ButtonDefaults.buttonColors(colors.error, colors.onError)) { Text(stringResource(R.string.retry)) }
        TextButton(onClick = { onMount(false) }, colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer)) {
            Text(stringResource(R.string.turn_off))
        }
    }
}

@Composable
private fun OffBody(volume: Volume, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Poster(volume.name, MaterialTheme.colorScheme.outline, maxSize = 48)
            MonoLabel("${stringResource(R.string.state_off)} · ${volume.export} · ${volume.version.label()}")
        }
        MountSwitch(volume, false, onToggle, onPrimary = false)
    }
}

/** Huge condensed name that shrinks to fit instead of wrapping. */
@Composable
private fun Poster(text: String, color: Color, maxSize: Int) {
    Text(
        text.uppercase(),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = maxSize.sp),
        style = MaterialTheme.typography.displayMedium,
    )
}

@Composable
private fun MountSwitch(volume: Volume, checked: Boolean, onToggle: () -> Unit, onPrimary: Boolean) {
    val haptics = LocalHapticFeedback.current
    val label = stringResource(if (checked) R.string.a11y_unmount else R.string.a11y_mount, volume.name)
    val c = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = {
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onToggle()
        },
        // On a primary card the default checked track (primary) would vanish; invert it.
        colors = if (onPrimary) SwitchDefaults.colors(
            checkedTrackColor = c.onPrimary,
            checkedThumbColor = c.primary,
            checkedIconColor = c.onPrimary,
        ) else SwitchDefaults.colors(),
        modifier = Modifier.semantics { contentDescription = label },
    )
}

// 30 fps is indistinguishable for motion this slow and lets the display drop from 120 Hz (Android 15+).
private const val DECOR_FPS = 30f

/** Slowly rotating expressive shape used as decoration behind cards and the empty state. */
@Composable
fun SpinningShape(color: Color, modifier: Modifier = Modifier, size: Int = 170, shape: androidx.graphics.shapes.RoundedPolygon? = null, periodMs: Int = 40_000) {
    val polygon = shape ?: MaterialShapes.Cookie9Sided
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    val clip = polygon.toShape()
    Box(modifier.size(size.dp).preferredFrameRate(DECOR_FPS).graphicsLayer { rotationZ = rotation }.clip(clip).background(color))
}

@Composable
private fun EmptyState(onAdd: () -> Unit, padding: PaddingValues) {
    val colors = MaterialTheme.colorScheme
    // One page, no scrolling: the illustration takes whatever height the text and button leave.
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 16.dp)) {
        MonoLabel("VibeNFS", size = 13)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val side = minOf(maxWidth, maxHeight, 320.dp).value.toInt()
            SpinningShape(colors.primary, Modifier.align(Alignment.Center), size = side * 15 / 16, shape = MaterialShapes.SoftBurst, periodMs = 60_000)
            SpinningShape(colors.tertiaryContainer, Modifier.align(Alignment.BottomEnd), size = side * 11 / 32, periodMs = 20_000)
            FolderBadge(MaterialShapes.Circle.toShape(), Color.Transparent, colors.onPrimary, side * 11 / 16, Modifier.align(Alignment.Center))
        }
        PosterTitle(stringResource(R.string.empty_title))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp).widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp), contentPadding = ButtonDefaults.MediumContentPadding) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.add_folder), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Android 17+ blocks LAN sockets without this runtime permission; ask once, then explain. */
@Composable
private fun LocalNetworkBanner() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(true) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // The permission only exists from Android 17 (API 37); older releases allow LAN sockets.
        granted = Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) launcher.launch(ACCESS_LOCAL_NETWORK)
    }
    // Only explain after the system dialog was answered, never on top of it.
    if (granted || !asked) return
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.local_network_rationale), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text(stringResource(R.string.grant)) }
        }
    }
}
