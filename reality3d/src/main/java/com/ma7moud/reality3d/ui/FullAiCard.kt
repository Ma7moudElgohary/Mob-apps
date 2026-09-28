package com.ma7moud.reality3d.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ma7moud.reality3d.export.ExportFile
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.mesh.GameReadyPack
import com.ma7moud.reality3d.preview.PreviewModel
import com.ma7moud.reality3d.remote.RemoteSettings

/** Full 3D, back included, from an image-to-3D AI on the user's computer. */
@Composable
internal fun FullAiCard(
    remote: RemoteUi,
    busy: Boolean,
    onSave: (url: String, token: String) -> Unit,
    onCheck: () -> Unit,
    onEngine: (String) -> Unit,
    onMake: () -> Unit,
    onCancel: () -> Unit,
) {
    var setup by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI Full 3D on your computer", style = MaterialTheme.typography.titleMedium)
            Text(
                "One photo can't show the back. An image-to-3D AI (Stable Fast 3D, Hunyuan3D-2 or TripoSR) on a " +
                    "computer with a graphics card makes a complete model. Start the server from the reality3d-server folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val server = remote.server
            when {
                remote.settings.url.isBlank() -> Button(onClick = { setup = true }, modifier = Modifier.fillMaxWidth()) { Text("Connect to your computer") }
                remote.checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Connecting to ${remote.settings.url}…", style = MaterialTheme.typography.bodyMedium)
                }
                server == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.weight(1f)) { Text("Try again") }
                    TextButton(onClick = { setup = true }) { Text("Change") }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Connected to ${remote.settings.url.substringAfter("://")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { setup = true }) { Text("Change") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        server.engines.filter { it.available }.forEach { engine ->
                            FilterChip(
                                selected = remote.settings.engine == engine.id,
                                onClick = { onEngine(engine.id) },
                                label = { Text(engine.name) },
                                enabled = remote.progress == null,
                            )
                        }
                    }
                    server.engines.firstOrNull { it.id == remote.settings.engine }?.note?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val missing = server.engines.filter { !it.available }.map { it.name }
                    if (missing.isNotEmpty()) {
                        Text(
                            "Not set up on the computer: ${missing.joinToString()}. See engines.example.toml.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val progress = remote.progress
                    if (progress == null) {
                        Button(
                            onClick = onMake,
                            enabled = !busy && server.engines.any { it.available && it.id == remote.settings.engine },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Make full 3D model") }
                    } else {
                        Text(progress.label, style = MaterialTheme.typography.bodyMedium)
                        val fraction = progress.fraction
                        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
            }
            remote.status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (remote.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (setup) {
        ServerDialog(remote.settings, onDismiss = { setup = false }, onSave = { url, token ->
            setup = false
            onSave(url, token)
        })
    }
}

@Composable
private fun ServerDialog(settings: RemoteSettings, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember { mutableStateOf(settings.url.substringAfter("http://")) }
    var token by remember { mutableStateOf(settings.token) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your computer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Run the Reality3D server on the computer and type the address it shows. The phone and the computer " +
                        "must be on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Address, e.g. 192.168.1.20:8765") })
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    label = { Text("Access code (if you set one)") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, token) }, enabled = url.isNotBlank()) { Text("Save and connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The AI's model: viewer, save, AR and exports. */
@Composable
internal fun AiModelSection(
    model: AiModel,
    useGlViewer: Boolean,
    metersPerUnit: Float,
    sizeKnown: Boolean,
    saved: Boolean,
    saving: Boolean,
    exporting: Boolean,
    export: suspend (ExportFormat, GameReadyPack.Budget) -> ExportFile?,
    holdForSaving: (ExportFile) -> Unit,
    savePending: (Uri?) -> Unit,
    onError: (String) -> Unit,
    onSave: () -> Unit,
    onViewInAr: (PreviewModel) -> Unit,
) {
    val actions = rememberExportActions(export, holdForSaving, savePending, onError)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Full 3D model · ${model.engine}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ViewerCard(
            mesh = model.mesh,
            texture = model.texture,
            photo = null,
            useGlViewer = useGlViewer,
            metersPerUnit = metersPerUnit,
            sizeKnown = sizeKnown,
            onSetRealLength = null,
            onScreenshot = actions.shareScreenshot,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSave, enabled = !saved && !saving, modifier = Modifier.weight(1f)) {
                Text(if (saved) "Saved to My models" else if (saving) "Saving…" else "Save to My models")
            }
            FilledTonalButton(onClick = { onViewInAr(PreviewModel(model.mesh, model.texture, metersPerUnit, "Full 3D model")) }) { Text("View in AR") }
        }
        ExportCard(SINGLE_PHOTO_FORMATS, solid = true, exporting = exporting, onShare = actions.share, onSave = actions.save)
    }
}
