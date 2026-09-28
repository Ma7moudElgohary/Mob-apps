package com.ma7moud.reality3d.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import com.ma7moud.reality3d.segmentation.MaskOps
import com.ma7moud.reality3d.segmentation.SubjectCandidate
import com.ma7moud.reality3d.segmentation.SubjectMask

@Composable
fun MaskEditor(
    bitmap: Bitmap,
    mask: SubjectMask,
    subjects: List<SubjectCandidate>,
    editMode: MaskOps.BrushMode?,
    brushRadius: Float,
    onMaskChanged: (SubjectMask) -> Unit,
    onSubjectSelected: (SubjectCandidate) -> Unit,
) {
    val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio)
            .pointerInput(subjects, editMode, brushRadius) {
                detectTapGestures { p ->
                    val u = p.x / size.width.coerceAtLeast(1); val v = p.y / size.height.coerceAtLeast(1)
                    if (editMode != null) onMaskChanged(MaskOps.brush(mask,u,v,brushRadius,editMode))
                    else subjects.firstOrNull { s ->
                        val x=u*bitmap.width; val y=v*bitmap.height
                        x>=s.startX && x<=s.startX+s.width && y>=s.startY && y<=s.startY+s.height
                    }?.let(onSubjectSelected)
                }
            }
            .pointerInput(mask, editMode, brushRadius) {
                if (editMode != null) detectDragGestures { change, _ ->
                    val u=change.position.x/size.width.coerceAtLeast(1); val v=change.position.y/size.height.coerceAtLeast(1)
                    onMaskChanged(MaskOps.brush(mask,u,v,brushRadius,editMode)); change.consume()
                }
            },
    ) {
        Image(bitmap.asImageBitmap(), contentDescription="Object", modifier=Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            subjects.forEach { s ->
                drawRect(Color(0xAA50E3FF), topLeft=Offset(s.startX/bitmap.width.toFloat()*size.width,s.startY/bitmap.height.toFloat()*size.height), size=androidx.compose.ui.geometry.Size(s.width/bitmap.width.toFloat()*size.width,s.height/bitmap.height.toFloat()*size.height), style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
        }
    }
}
