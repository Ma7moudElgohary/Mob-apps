package com.ma7moud.reality3d.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Puts photos taken in the app where the user looks for photos: the phone's Gallery. */
object GalleryExport {

    /** Where saved photos are shown in the Gallery. */
    const val FOLDER = "Pictures/Reality3D"

    /** Whether photos can be added to the Gallery without asking for a permission. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Copies the JPEG at [source] into the Gallery, in [FOLDER]. Returns where it went, or null when the phone
     * needs a permission for it (before Android 10) or the Gallery refused the file.
     */
    fun save(context: Context, source: Uri, now: Long = System.currentTimeMillis()): Uri? {
        if (!available) return null
        val resolver = context.contentResolver
        val name = "Reality3D_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            // Hidden from other apps until the copy is complete.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val copied = resolver.openInputStream(source)?.use { from ->
                resolver.openOutputStream(target)?.use { to ->
                    from.copyTo(to)
                    true
                }
            }
            if (copied != true) throw IOException("couldn't copy the photo into the Gallery")
            resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return target
        } catch (e: Exception) {
            // Don't leave half a photo in the Gallery.
            try {
                resolver.delete(target, null, null)
            } catch (_: Exception) {
            }
            throw e
        }
    }
}
