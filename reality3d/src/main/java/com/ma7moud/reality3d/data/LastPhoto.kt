package com.ma7moud.reality3d.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The photo being worked on, kept on disk so that if the app closes unexpectedly the user finds it again when
 * they reopen it. It is the upright photo the app decoded, as a JPEG.
 */
class LastPhoto(private val directory: File) {

    private val file = File(directory, "last_photo.jpg")

    fun save(photo: Bitmap) {
        directory.mkdirs()
        val partial = File(directory, "last_photo.jpg.part")
        partial.outputStream().use { check(photo.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) { "couldn't encode the photo" } }
        check(partial.renameTo(file)) { "couldn't keep the photo" }
    }

    /** The kept photo, or null if there is none or it can't be read. */
    fun load(): Bitmap? {
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun clear() {
        file.delete()
        File(directory, "last_photo.jpg.part").delete()
    }

    private companion object {
        const val QUALITY = 92
    }
}
