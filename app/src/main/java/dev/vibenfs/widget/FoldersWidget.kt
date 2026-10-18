package dev.vibenfs.widget

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.vibenfs.MainActivity
import dev.vibenfs.R
import dev.vibenfs.data.Volume
import dev.vibenfs.mount.MountManager
import dev.vibenfs.mount.MountState
import dev.vibenfs.mounts
import dev.vibenfs.ui.friendlyErrorRes

/** Home screen twin of the Quick Settings tile: mount everything at once, or one folder at a time. */
class FoldersWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val mounts = context.mounts
        // Collected live while the session runs; MountManager's updateAll restarts it once it has ended.
        // Plain .value reads would not recompose a running session.
        provideContent {
            val volumes by mounts.volumes.collectAsState()
            val states by mounts.states.collectAsState()
            GlanceTheme { Content(context, volumes.orEmpty(), states) }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = FoldersWidget().updateAll(context)
    }
}

class FoldersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FoldersWidget()
}

private val VolumeId = ActionParameters.Key<String>("volume")

class ToggleAllAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.mounts.toggleAll()
    }
}

class MountAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Toggle against current state, not the state the widget was rendered with.
        context.mounts.toggle(parameters[VolumeId] ?: return)
    }
}

@Composable
private fun Content(context: Context, volumes: List<Volume>, states: Map<String, MountState>) {
    val colors = GlanceTheme.colors
    val mounted = volumes.count { it.mounted }
    val anyMounted = mounted > 0
    Column(
        GlanceModifier.fillMaxSize().background(colors.widgetBackground).cornerRadius(28.dp).padding(12.dp),
    ) {
        // Header doubles as the global toggle, like tapping the tile.
        Row(
            GlanceModifier.fillMaxWidth()
                .background(if (anyMounted) colors.primary else colors.secondaryContainer)
                .cornerRadius(22.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(actionRunCallback<ToggleAllAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onColor = if (anyMounted) colors.onPrimary else colors.onSecondaryContainer
            Image(ImageProvider(R.drawable.ic_tile_nfs), null, GlanceModifier.size(24.dp), colorFilter = androidx.glance.ColorFilter.tint(onColor))
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    context.getString(R.string.folders_poster).uppercase(),
                    style = TextStyle(color = onColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily("sans-serif-condensed")),
                )
                Text(
                    context.getString(R.string.mounted_ratio, mounted, volumes.size),
                    style = TextStyle(color = onColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                )
            }
            Text(
                context.getString(if (anyMounted) R.string.unmount_all else R.string.mount_all),
                style = TextStyle(color = onColor, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
        }
        // Compact sizes show only the global toggle.
        if (LocalSize.current.height < 120.dp) return@Column
        Spacer(GlanceModifier.height(8.dp))
        LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
            items(volumes) { v -> VolumeRow(context, v, states[v.id]) }
        }
    }
}

private fun size(context: Context, bytes: Long) = Formatter.formatShortFileSize(context, bytes)

/** The whole row is the toggle: it lights up in the primary colors while the folder is mounted. */
@Composable
private fun VolumeRow(context: Context, volume: Volume, state: MountState?) {
    val colors = GlanceTheme.colors
    val error = state is MountState.Error
    val lit = volume.mounted && !error
    // The lit row already says "mounted": spend the second line on what's useful instead.
    val subtitle = when (state) {
        is MountState.Error -> friendlyErrorRes(state.message)?.let(context::getString) ?: state.message
        is MountState.Mounted -> state.freeBytes?.let { free ->
            state.totalBytes?.let { context.getString(R.string.free_of_total, size(context, free), size(context, it)) }
                ?: context.getString(R.string.free_short, size(context, free))
        } ?: volume.host
        MountState.Connecting -> context.getString(R.string.state_connecting) + "…"
        else -> if (volume.mounted) context.getString(R.string.state_connecting) + "…" else volume.host
    }
    val used = (state as? MountState.Mounted)?.let { m ->
        if (m.totalBytes != null && m.freeBytes != null && m.totalBytes > 0) 1f - m.freeBytes.toFloat() / m.totalBytes else null
    }
    val (container, title, body) = when {
        error -> Triple(colors.errorContainer, colors.onErrorContainer, colors.onErrorContainer)
        lit -> Triple(colors.primary, colors.onPrimary, colors.onPrimary)
        else -> Triple(colors.surfaceVariant, colors.onSurface, colors.onSurfaceVariant)
    }
    Column(GlanceModifier.padding(vertical = 3.dp)) {
        Row(
            GlanceModifier.fillMaxWidth()
                .background(container)
                .cornerRadius(20.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(actionRunCallback<MountAction>(actionParametersOf(VolumeId to volume.id))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Widgets get no long-press (the launcher keeps it for moving), so the secondary actions
            // sit on the row: the icon chip opens the app, the trailing button opens Files.
            // Icon chip: filled when on, outline-quiet when off, so state reads at a glance.
            Box(
                GlanceModifier.size(36.dp).cornerRadius(18.dp).background(if (lit) colors.onPrimary else colors.surface)
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(R.drawable.ic_tile_nfs), context.getString(R.string.open_app), GlanceModifier.size(20.dp),
                    colorFilter = androidx.glance.ColorFilter.tint(if (lit) colors.primary else body),
                )
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(volume.name, maxLines = 1, style = TextStyle(color = title, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Text(subtitle, maxLines = 1, style = TextStyle(color = body, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                if (used != null) {
                    Spacer(GlanceModifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = used,
                        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                        color = colors.onPrimary,
                        backgroundColor = ColorProvider(R.color.widget_track),
                    )
                }
            }
            if (state is MountState.Mounted) {
                Spacer(GlanceModifier.width(6.dp))
                val root = DocumentsContract.buildRootUri(MountManager.AUTHORITY, volume.id)
                Box(
                    GlanceModifier.size(36.dp).cornerRadius(18.dp)
                        .clickable(actionStartActivity(Intent(Intent.ACTION_VIEW).setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(R.drawable.ic_open_in_new), context.getString(R.string.open_in_files), GlanceModifier.size(20.dp),
                        colorFilter = androidx.glance.ColorFilter.tint(title),
                    )
                }
            }
        }
    }
}
