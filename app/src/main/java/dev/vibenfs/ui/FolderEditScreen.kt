@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.vibenfs.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.vibenfs.R
import dev.vibenfs.data.Volume
import dev.vibenfs.mounts
import dev.vibenfs.nfs.NfsClient
import dev.vibenfs.nfs.NfsVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface TestResult {
    data object Running : TestResult
    data class Ok(val freeBytes: Long?) : TestResult
    data class Failed(val message: String) : TestResult
}

@Composable
fun FolderEditScreen(id: String?, onBack: () -> Unit) {
    val mounts = LocalContext.current.mounts
    val existing = remember(id) { mounts.volumes.value?.firstOrNull { it.id == id } }
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var export by rememberSaveable { mutableStateOf(existing?.export.orEmpty()) }
    var version by rememberSaveable { mutableStateOf(existing?.version ?: NfsVersion.AUTO) }
    var port by rememberSaveable { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var uid by rememberSaveable { mutableStateOf(existing?.uid?.takeIf { it >= 0 }?.toString().orEmpty()) }
    var gid by rememberSaveable { mutableStateOf(existing?.gid?.takeIf { it >= 0 }?.toString().orEmpty()) }
    var readOnly by rememberSaveable { mutableStateOf(existing?.readOnly ?: false) }
    var advanced by rememberSaveable { mutableStateOf(port.isNotEmpty() || uid.isNotEmpty() || gid.isNotEmpty()) }
    var revealAdvanced by remember { mutableStateOf(false) }
    val advancedView = remember { BringIntoViewRequester() }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exports by remember { mutableStateOf(emptyList<String>()) }
    // When adding, more exports of the same server can be ticked: each becomes its own folder.
    var extra by rememberSaveable { mutableStateOf(listOf<String>()) }
    var test by remember { mutableStateOf<TestResult?>(null) }

    // Ask the server what it shares once the user stops typing the address.
    LaunchedEffect(host, port) {
        exports = emptyList()
        if (host.isBlank()) return@LaunchedEffect
        delay(600)
        exports = withContext(Dispatchers.IO) { runCatching { NfsClient.exports(host.trim(), port.toIntOrNull() ?: 0) }.getOrDefault(emptyList()) }
    }


    fun build(mounted: Boolean, path: String = export, label: String = name.ifBlank { host }): Volume? {
        showErrors = true
        if (host.isBlank() || path.isBlank()) return null
        val base = existing ?: Volume(name = "", host = "", export = "")
        return base.copy(
            name = label.trim(),
            host = host.trim(),
            export = path.trim(),
            version = version,
            port = port.toIntOrNull() ?: 0,
            uid = uid.toIntOrNull() ?: -1,
            gid = gid.toIntOrNull() ?: -1,
            readOnly = readOnly,
            mounted = mounted,
        )
    }

    fun save(mounted: Boolean) {
        val paths = (listOf(export) + extra).filter { it.isNotBlank() }.distinct()
        val volumes = if (paths.size <= 1) listOfNotNull(build(mounted, paths.firstOrNull() ?: export))
        else paths.mapNotNull { build(mounted, it, folderLabel(it, paths, name)) }
        if (volumes.isEmpty()) return
        volumes.forEach { mounts.saveInBackground(it) }
        onBack()
    }

    // Surface, not a bare background: it also sets the content color, so text reads in dark theme.
    Surface(color = colors.surfaceContainer, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
            }
            Text(
                stringResource(if (existing == null) R.string.add_folder else R.string.edit_folder),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (existing != null) FilledIconButton(
                onClick = { confirmDelete = true },
                shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(colors.errorContainer, colors.onErrorContainer),
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FolderBadge(MaterialShapes.Sunny.toShape(), colors.primary, colors.onPrimary, 84)
            Column(Modifier.weight(1f)) {
                MonoLabel(stringResource(R.string.field_name), size = 11)
                val style = MaterialTheme.typography.displayMedium.copy(color = colors.primary)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text(host.ifBlank { stringResource(R.string.field_name) }.uppercase(), style = style.copy(color = colors.outline))
                        inner()
                    },
                )
            }
        }

        val required = stringResource(R.string.error_required)
        BigField(
            stringResource(R.string.field_host), host, { host = it; extra = emptyList() }, // ticks belong to the old server
            placeholder = stringResource(R.string.field_host_hint),
            keyboardType = KeyboardType.Uri,
            error = required.takeIf { showErrors && host.isBlank() },
        )

        BigField(
            stringResource(R.string.field_export), export, { export = it },
            placeholder = "/volume1/media",
            keyboardType = KeyboardType.Uri,
            error = required.takeIf { showErrors && export.isBlank() },
        )
        if (exports.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.discovered_exports), style = MaterialTheme.typography.titleSmall)
                    if (existing == null) Text(
                        stringResource(R.string.pick_several_exports),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                MonoLabel("${exports.size} · $host", size = 11)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                exports.forEach { path ->
                    FilterChip(
                        selected = export == path || path in extra,
                        onClick = {
                            when {
                                existing != null -> export = path // editing one folder: just switch path
                                export == path -> { export = extra.firstOrNull().orEmpty(); extra = extra.drop(1) }
                                path in extra -> extra = extra - path
                                export.isBlank() -> export = path
                                else -> extra = extra + path
                            }
                        },
                        label = { MonoLabel(path, color = MaterialTheme.colorScheme.onSurface, size = 14, uppercase = false) },
                        leadingIcon = if (export == path || path in extra) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.field_version), style = MaterialTheme.typography.titleSmall)
            val options = NfsVersion.entries
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                options.forEachIndexed { i, v ->
                    ToggleButton(
                        checked = version == v,
                        onCheckedChange = { version = v },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = ToggleButtonDefaults.colors(containerColor = colors.surfaceContainerLowest),
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) { Text(v.label()) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.field_read_only), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.read_only_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Switch(checked = readOnly, onCheckedChange = { readOnly = it })
        }

        TextButton(onClick = { advanced = !advanced; revealAdvanced = advanced }) {
            Text(stringResource(R.string.advanced))
            Icon(if (advanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null)
        }
        AnimatedVisibility(advanced, Modifier.bringIntoViewRequester(advancedView)) {
            // The section opens below the fold, often unnoticed: scroll it into view once it has grown.
            if (revealAdvanced) LaunchedEffect(Unit) {
                snapshotFlow { transition.currentState }.first { it == EnterExitState.Visible }
                advancedView.bringIntoView()
                revealAdvanced = false
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigField(
                    stringResource(R.string.field_port), port, { extra = emptyList(); port = it.filter(Char::isDigit).take(5).takeIf { p -> (p.toIntOrNull() ?: 0) <= 65535 } ?: port },
                    placeholder = stringResource(R.string.field_port_hint), keyboardType = KeyboardType.Number,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigField(
                        stringResource(R.string.field_uid), uid, { uid = it.filter(Char::isDigit).take(9) },
                        placeholder = "—", keyboardType = KeyboardType.Number, valueSize = 34, valueColor = colors.primary,
                        modifier = Modifier.weight(1f),
                    )
                    BigField(
                        stringResource(R.string.field_gid), gid, { gid = it.filter(Char::isDigit).take(9) },
                        placeholder = "—", keyboardType = KeyboardType.Number, valueSize = 34, valueColor = colors.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        test?.let { TestCard(it) }
    }
        // Pinned action bar: always reachable, even with the keyboard up.
        // Flow, so at large font scales the save button moves to its own line instead of squeezing.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 640.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                val v = build(false) ?: return@TextButton
                test = TestResult.Running
                scope.launch {
                    test = withContext(Dispatchers.IO) {
                        runCatching { NfsClient.mount(v.host, v.export, v.version, v.port, v.uid, v.gid).use { runCatching { it.space().second }.getOrNull() } }
                            .fold({ TestResult.Ok(it) }, {
                                Log.w("VibeNFS", "test ${v.host}:${v.export} (${v.version}) failed", it)
                                TestResult.Failed(it.message ?: "")
                            })
                    }
                }
            }) { Text(stringResource(R.string.test_connection)) }
            // Plain save keeps the current mount state; only the primary action mounts.
            SaveSplitButton(onSaveAndMount = { save(true) }, onSave = { save(existing?.mounted ?: false) })
        }
    }
    }

    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.delete_confirm, existing.name)) },
        confirmButton = {
            TextButton(onClick = {
                mounts.deleteInBackground(existing.id)
                onBack()
            }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TestCard(result: TestResult) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = if (result is TestResult.Failed) colors.errorContainer to colors.onErrorContainer
    else colors.tertiaryContainer to colors.onTertiaryContainer
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                when (result) {
                    TestResult.Running -> LoadingIndicator(color = content)
                    is TestResult.Ok -> Icon(Icons.Rounded.Check, null)
                    is TestResult.Failed -> Icon(Icons.Rounded.ErrorOutline, null)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when (result) {
                        TestResult.Running -> stringResource(R.string.test_connection) + "…"
                        is TestResult.Ok -> stringResource(R.string.test_ok, result.freeBytes?.let { bytes(it) } ?: "—")
                        is TestResult.Failed -> stringResource(R.string.connection_error)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (result is TestResult.Failed) ErrorText(result.message, content, size = 11)
            }
        }
    }
}

@Composable
private fun SaveSplitButton(onSaveAndMount: () -> Unit, onSave: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = onSaveAndMount,
                shapes = SplitButtonDefaults.leadingButtonShapesFor(SplitButtonDefaults.MediumContainerHeight),
                contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(SplitButtonDefaults.MediumContainerHeight),
            ) { Text(stringResource(R.string.save_and_mount), style = MaterialTheme.typography.titleMedium) }
        },
        trailingButton = {
            Box {
                SplitButtonDefaults.TrailingButton(
                    checked = menu,
                    onCheckedChange = { menu = it },
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(SplitButtonDefaults.MediumContainerHeight),
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(SplitButtonDefaults.MediumContainerHeight),
                ) { Icon(if (menu) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, stringResource(R.string.more_save_options)) }
                // The screen is surfaceContainer, the menu's default: give it its own color so it stands out.
                val colors = MaterialTheme.colorScheme
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = colors.tertiaryContainer,
                    shadowElevation = 6.dp,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium) },
                        leadingIcon = { Icon(Icons.Rounded.Save, null) },
                        colors = MenuDefaults.itemColors(textColor = colors.onTertiaryContainer, leadingIconColor = colors.onTertiaryContainer),
                        onClick = { menu = false; onSave() },
                    )
                }
            }
        },
    )
}

/**
 * Name for one of several exports added together: its last path segment ("/srv/nfs4/HDD" -> "HDD"),
 * with the parent when two picks share it, and the typed name in front when there is one.
 */
internal fun folderLabel(path: String, all: List<String>, typed: String): String {
    fun segs(p: String) = p.trim('/').split('/').filter { it.isNotEmpty() }
    val mine = segs(path)
    val clash = all.count { segs(it).lastOrNull() == mine.lastOrNull() } > 1
    val base = (if (clash) mine.takeLast(2) else mine.takeLast(1)).joinToString("/").ifEmpty { "/" }
    return if (typed.isBlank()) base else "${typed.trim()} $base"
}
