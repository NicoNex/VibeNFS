package dev.vibenfs.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibenfs.R
import dev.vibenfs.nfs.NfsVersion
import androidx.compose.ui.res.stringResource
import dev.vibenfs.ui.theme.LocalMonoFamily

/** Small all-caps monospace caption, the "data" voice of the editorial layout. */
@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Int = 12,
    uppercase: Boolean = true,
) {
    Text(
        if (uppercase) text.uppercase() else text,
        modifier = modifier,
        color = color,
        style = TextStyle(fontFamily = LocalMonoFamily.current, fontWeight = FontWeight.Medium, fontSize = size.sp, letterSpacing = 0.6.sp),
    )
}

/** Big monospace figure with a small unit, e.g. "1.24 TB". */
@Composable
fun MonoStat(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, color = color, style = TextStyle(fontFamily = LocalMonoFamily.current, fontWeight = FontWeight.Bold, fontSize = 22.sp))
        MonoLabel(label, color = color, size = 11)
    }
}

@Composable
fun StatusPill(text: String, container: Color, content: Color) {
    Row(
        Modifier.clip(CircleShape).background(container).heightIn(min = 28.dp).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(content))
        MonoLabel(text, color = content)
    }
}

/** The app glyph centered on an expressive shape. */
@Composable
fun FolderBadge(shape: Shape, container: Color, content: Color, size: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_tile_nfs), null, tint = content, modifier = Modifier.size((size * 0.42f).dp))
    }
}

/** Label-over-value field: mono caption, large value, tonal container. */
@Composable
fun BigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    valueSize: Int = 22,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    error: String? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        MonoLabel(error ?: label, color = if (error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant, size = 11)
        val style = TextStyle(fontFamily = LocalMonoFamily.current, fontWeight = FontWeight.SemiBold, fontSize = valueSize.sp, color = valueColor)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.outline))
                inner()
            },
        )
    }
}

@Composable
fun bytes(value: Long): String = Formatter.formatShortFileSize(LocalContext.current, value)

/** Uppercase condensed headline in the editorial voice. */
@Composable
fun PosterTitle(text: String, modifier: Modifier = Modifier, size: Int = 104, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.displayLarge.copy(fontSize = size.sp, lineHeight = (size * 0.86f).sp),
    )
}

/** Readable error plus, when it was translated, the server's own words underneath for diagnosis. */
@Composable
fun ErrorText(raw: String, color: Color, size: Int = 12) {
    val friendly = friendlyError(raw)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MonoLabel(friendly, color = color, size = size)
        if (friendly != raw) MonoLabel(raw, color = color.copy(alpha = 0.7f), size = 10, uppercase = false)
    }
}

/** Turns libnfs/RPC error text into something a person can act on; unknown errors pass through. */
@Composable
fun friendlyError(raw: String): String {
    val m = raw.lowercase()
    return when {
        // Linux nfsd answers a client on a non-privileged port (all Android apps) this way unless the
        // export has "insecure": v4 rejects the RPC, v3's MOUNT says ACCES.
        "not accepted" in m || "mnt3err_acces" in m -> stringResource(R.string.error_insecure)
        "refused" in m -> stringResource(R.string.error_refused)
        "no route" in m || "unreachable" in m -> stringResource(R.string.error_unreachable)
        "timed out" in m || "timeout" in m -> stringResource(R.string.error_timeout)
        "permission" in m || "access" in m -> stringResource(R.string.error_denied)
        "no such" in m || "noent" in m || "not found" in m -> stringResource(R.string.error_no_export)
        else -> raw
    }
}

@Composable
fun NfsVersion.label() = when (this) {
    NfsVersion.AUTO -> stringResource(R.string.version_auto)
    NfsVersion.V3 -> "v3"
    NfsVersion.V4 -> "v4"
}
