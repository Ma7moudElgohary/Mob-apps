package com.ma7moud.reality3d.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.ma7moud.reality3d.remote.BuildMode
import com.ma7moud.reality3d.remote.BuildQuality

/**
 * 3D from many photos: photos taken with the normal camera go to the computer's photo builder (the way RealityScan or
 * KIRI Engine work them out), and a detailed, textured model comes back to My models.
 */
@Composable
internal fun PhotosCard(
    remote: RemoteUi,
    build: PhotoBuildUi,
    onPickPhotos: () -> Unit,
    onClearPhotos: () -> Unit,
    onQuality: (BuildQuality) -> Unit,
    onMode: (BuildMode) -> Unit,
    onBuild: () -> Unit,
    onCancel: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenModel: (String) -> Unit,
    onSaveServer: (url: String, token: String) -> Unit,
    onCheckServer: () -> Unit,
) {
    var setup by remember { mutableStateOf(false) }
    val server = remote.server
    val builder = server?.engines?.firstOrNull { it.kind == "photos" }
    val ready = builder?.available == true
    val working = build.progress != null
    KeepScreenOn(working)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("3D from many photos", style = MaterialTheme.typography.titleMedium)
            Text(
                "The most detailed way: photos from all around the object, worked out on your computer the way RealityScan and " +
                    "KIRI Engine do it. Take 40 to 80 photos with the normal camera app, about every 10°, at two or three heights, " +
                    "in even light and without zooming. Pick them here. Nothing leaves your Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                remote.settings.url.isBlank() -> OutlinedButton(onClick = { setup = true }, modifier = Modifier.fillMaxWidth()) { Text("Connect your computer") }
                remote.checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Connecting to ${remote.settings.url}…", style = MaterialTheme.typography.bodyMedium)
                }
                server == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCheckServer, modifier = Modifier.weight(1f)) { Text("Check again") }
                    TextButton(onClick = { setup = true }) { Text("Change address") }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                builder == null -> "Connected, but this server is older and has no photo builder. Update the reality3d-server folder."
                                ready -> "Connected to ${remote.settings.url.substringAfter("://")}. The photo builder is ready."
                                else -> "Connected, but the photo builder isn't ready: ${builder.why ?: "something is missing on the computer."}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { setup = true }) { Text("Change address") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val label = if (build.picked.isEmpty()) "Choose photos" else "Choose other photos"
                if (build.picked.isEmpty()) {
                    FilledTonalButton(onClick = onPickPhotos, enabled = !working) { Text(label) }
                } else {
                    OutlinedButton(onClick = onPickPhotos, enabled = !working) { Text(label) }
                }
                if (build.picked.isNotEmpty()) {
                    Text(
                        "${build.picked.size} photos",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearPhotos, enabled = !working) { Text("Clear") }
                }
            }
            if (build.picked.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuildMode.entries.forEach { mode ->
                        FilterChip(selected = build.options.mode == mode, onClick = { onMode(mode) }, label = { Text(mode.label) }, enabled = !working)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuildQuality.entries.forEach { quality ->
                        FilterChip(selected = build.options.quality == quality, onClick = { onQuality(quality) }, label = { Text(quality.label) }, enabled = !working)
                    }
                }
                Text(
                    "${build.options.quality.hint}. ${if (build.options.mode == BuildMode.OBJECT) "Keeps to the object in the middle." else "Keeps everything the photos show."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (build.picked.size < Reality3DViewModel.MIN_BUILD_PHOTOS) {
                    Text(
                        "At least ${Reality3DViewModel.MIN_BUILD_PHOTOS} photos are needed; 40 or more work best.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val progress = build.progress
            if (progress == null) {
                Button(
                    onClick = onBuild,
                    enabled = ready && build.picked.size >= Reality3DViewModel.MIN_BUILD_PHOTOS,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Build the model on my computer") }
            } else {
                Text(progress.label, style = MaterialTheme.typography.bodyMedium)
                val fraction = progress.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "A few minutes, longer for more photos or higher quality. Keep this screen open; it stays awake.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            build.savedId?.let { id ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Saved to My models as “${build.savedName}”.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { onOpenModel(id) }) { Text("Open") }
                }
            }
            build.status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (build.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenFile, enabled = !working) { Text("Open a .glb from another app (KIRI Engine, Scaniverse, Polycam…)") }
        }
    }
    if (setup) {
        ServerDialog(remote.settings, onDismiss = { setup = false }, onSave = { url, token ->
            setup = false
            onSaveServer(url, token)
        })
    }
}
