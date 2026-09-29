package com.ma7moud.reality3d.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.preview.PreviewModel
import com.ma7moud.reality3d.project.Project
import com.ma7moud.reality3d.project.ProjectInfo
import com.ma7moud.reality3d.project.ProjectKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Recent saved models on the home screen. */
@Composable
internal fun MyModelsRow(projects: List<ProjectInfo>, onOpen: (String) -> Unit, onSeeAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My models", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAll) { Text("See all (${projects.size})") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(projects.take(RECENT), key = { it.id }) { project ->
                Column(
                    Modifier
                        .width(116.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpen(project.id) },
                ) {
                    Thumbnail(project.thumbnail, 116.dp)
                    Text(
                        project.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Every saved model, newest first. */
@Composable
internal fun GalleryScreen(viewModel: ProjectsViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("My models", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        message?.let { (text, isError) -> Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        val list = projects
        when {
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            list.isEmpty() -> Text(
                "No saved models yet. Make one from a photo or a 360° scan, then tap Save to My models.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { project ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(project.id) }, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumbnail(project.thumbnail, 76.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(summary(project), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(details(project), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { deleting = project }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { project ->
        ConfirmDelete(project.name, onConfirm = {
            deleting = null
            viewModel.delete(project.id)
        }, onDismiss = { deleting = null })
    }
}

/** One saved model: the viewer, its details and exports. */
@Composable
internal fun ProjectScreen(
    viewModel: ProjectsViewModel,
    projectId: String,
    useGlViewer: Boolean,
    onViewInAr: (PreviewModel) -> Unit,
    onBack: () -> Unit,
) {
    val project by viewModel.current.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val actions = rememberExportActions(
        export = viewModel::export,
        holdForSaving = viewModel::holdForSaving,
        savePending = viewModel::savePending,
        onError = { viewModel.showMessage(it, isError = true) },
    )
    LaunchedEffect(projectId) {
        if (viewModel.current.value?.info?.id != projectId) viewModel.open(projectId)
    }
    BackHandler(onBack = onBack)
    val open = project?.takeIf { it.info.id == projectId }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                open?.info?.name ?: "Model",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (open != null) TextButton(onClick = { renaming = true }) { Text("Rename") }
        }
        message?.let { (text, isError) -> Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        if (open == null) {
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            OpenProject(open, useGlViewer, viewModel, actions, exporting, onViewInAr, onDelete = { deleting = true })
        }
        Spacer(Modifier.height(8.dp))
    }
    if (renaming && open != null) {
        RenameDialog(open.info.name, onDismiss = { renaming = false }, onRename = {
            renaming = false
            viewModel.rename(it)
        })
    }
    if (deleting && open != null) {
        ConfirmDelete(open.info.name, onConfirm = {
            deleting = false
            viewModel.delete(open.info.id)
            onBack()
        }, onDismiss = { deleting = false })
    }
}

@Composable
private fun OpenProject(
    open: Project,
    useGlViewer: Boolean,
    viewModel: ProjectsViewModel,
    actions: ExportActions,
    exporting: Boolean,
    onViewInAr: (PreviewModel) -> Unit,
    onDelete: () -> Unit,
) {
    val info = open.info
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ViewerCard(
            mesh = open.mesh,
            texture = open.texture,
            photo = open.photo,
            useGlViewer = useGlViewer,
            metersPerUnit = open.metersPerUnit,
            sizeKnown = info.sizeKnown,
            onSetRealLength = if (info.kind != ProjectKind.SCAN) viewModel::setRealLength else null,
            onScreenshot = actions.shareScreenshot,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(summary(info), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { onViewInAr(PreviewModel(open.mesh, open.texture, open.metersPerUnit, info.name)) }) { Text("View in AR") }
        }
        info.quality?.let { report ->
            QualityCard(
                title = when (info.kind) {
                    ProjectKind.SCAN -> "Scan quality"
                    ProjectKind.PHOTOGRAMMETRY -> "Photo set quality"
                    else -> "Photo quality"
                },
                report = report,
                goodText = "Nothing held this capture back.",
            )
        }
        ExportCard(
            formats = if (info.kind == ProjectKind.SCAN) ExportFormat.entries - ExportFormat.PHOTOS else SINGLE_PHOTO_FORMATS,
            solid = open.mesh.solid,
            exporting = exporting,
            onShare = actions.share,
            onSave = actions.save,
        )
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete this model") }
    }
}

@Composable
private fun Thumbnail(file: File, size: Dp) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color(0xFF12203A)), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onRename(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDelete(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “$name”?") },
        text = { Text("The model is removed from this phone. Files you exported stay where you saved them.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

private fun summary(project: ProjectInfo): String =
    when (project.kind) {
        ProjectKind.SCAN -> "360° scan"
        ProjectKind.PHOTO -> "From a photo"
        ProjectKind.AI -> "AI full 3D"
        ProjectKind.PHOTOGRAMMETRY -> "From many photos"
        ProjectKind.IMPORTED -> "Opened from a file"
    } + " · " +
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.createdAt)) +
        (project.quality?.let { " · quality ${it.score}/100" } ?: "")

private fun details(project: ProjectInfo): String =
    "${project.triangles} triangles · " + (if (project.sizeKnown) "" else "≈ ") + formatSize(project.size)

private const val RECENT = 8
