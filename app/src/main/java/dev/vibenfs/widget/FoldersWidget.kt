package dev.vibenfs.widget

import android.content.Context
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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
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
import dev.vibenfs.mount.MountState
import dev.vibenfs.mounts

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

@Composable
private fun VolumeRow(context: Context, volume: Volume, state: MountState?) {
    val colors = GlanceTheme.colors
    val error = state is MountState.Error
    val subtitle = when (state) {
        is MountState.Error -> context.getString(R.string.state_error)
        MountState.Connecting -> context.getString(R.string.state_connecting) + "…"
        is MountState.Mounted -> context.getString(R.string.state_mounted)
        else -> if (volume.mounted) context.getString(R.string.state_connecting) + "…" else context.getString(R.string.state_off)
    }
    Column(GlanceModifier.padding(vertical = 3.dp)) {
        Row(
            GlanceModifier.fillMaxWidth()
                .background(if (error) colors.errorContainer else colors.surfaceVariant)
                .cornerRadius(20.dp)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onColor: ColorProvider = if (error) colors.onErrorContainer else colors.onSurfaceVariant
            Column(GlanceModifier.defaultWeight()) {
                Text(volume.name, maxLines = 1, style = TextStyle(color = if (error) colors.onErrorContainer else colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Text(subtitle.uppercase(), style = TextStyle(color = onColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
            }
            Switch(
                checked = volume.mounted,
                onCheckedChange = actionRunCallback<MountAction>(actionParametersOf(VolumeId to volume.id)),
            )
        }
    }
}
