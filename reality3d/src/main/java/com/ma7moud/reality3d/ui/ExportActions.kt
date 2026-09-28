package com.ma7moud.reality3d.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.ma7moud.reality3d.export.ExportFile
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.mesh.GameReadyPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Sharing and saving a model or a screenshot, with the system's share sheet and "save as" screen. */
internal class ExportActions(
    val share: (ExportFormat, GameReadyPack.Budget) -> Unit,
    val save: (ExportFormat, GameReadyPack.Budget) -> Unit,
    val shareScreenshot: (Bitmap) -> Unit,
)

@Composable
internal fun rememberExportActions(
    export: suspend (ExportFormat, GameReadyPack.Budget) -> ExportFile?,
    holdForSaving: (ExportFile) -> Unit,
    savePending: (Uri?) -> Unit,
    onError: (String) -> Unit,
): ExportActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveGlb = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.GLB.mimeType), savePending)
    val saveStl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.STL.mimeType), savePending)
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.OBJ.mimeType), savePending)
    val savePly = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PLY.mimeType), savePending)

    fun failure(what: String, e: Exception) = onError("Couldn't $what: ${e.message ?: e.javaClass.simpleName}.")

    return ExportActions(
        share = { format, budget ->
            scope.launch {
                try {
                    val file = export(format, budget) ?: return@launch
                    context.startActivity(Exporter.shareIntent(context, file))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure("share the model", e)
                }
            }
        },
        save = { format, budget ->
            scope.launch {
                try {
                    val file = export(format, budget) ?: return@launch
                    holdForSaving(file)
                    when (format) {
                        ExportFormat.GLB -> saveGlb.launch(file.fileName)
                        ExportFormat.STL -> saveStl.launch(file.fileName)
                        ExportFormat.OBJ, ExportFormat.UNREAL, ExportFormat.PHOTOS -> saveZip.launch(file.fileName)
                        ExportFormat.PLY -> savePly.launch(file.fileName)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure("save the model", e)
                }
            }
        },
        shareScreenshot = { bitmap ->
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.Default) { Exporter.png(bitmap) }
                    context.startActivity(Exporter.shareIntent(context, Exporter.screenshotName(), "image/png", bytes))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure("share the picture", e)
                }
            }
        },
    )
}
