package com.ma7moud.reality3d.remote

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.ma7moud.reality3d.scan.PhotoSet
import com.ma7moud.reality3d.scan.PhotoSetWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The photos as one zip file, the way the computer's photo builder takes them. */
object PhotoZip {

    /** The most photos the builder accepts. */
    const val MAX_PHOTOS = 400

    class Zipped(val file: File, val photos: Int, val skipped: Int)

    /**
     * Zips the photos behind [uris] into [target]. JPEGs go in exactly as the camera made them (so their details, like
     * the lens, stay for the builder); any other format is turned into a JPEG. Photos that can't be read are left out.
     */
    fun fromUris(context: Context, uris: List<Uri>, target: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Zipped {
        val resolver = context.contentResolver
        var added = 0
        var skipped = 0
        ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
            zip.setLevel(0) // photos are compressed already
            uris.take(MAX_PHOTOS).forEachIndexed { index, uri ->
                onProgress(index, uris.size)
                val name = String.format(Locale.ROOT, "images/photo_%03d.jpg", added)
                if (addPhoto(resolver, uri, zip, name)) added++ else skipped++
            }
            onProgress(uris.size, uris.size)
        }
        return Zipped(target, added, skipped + (uris.size - uris.take(MAX_PHOTOS).size))
    }

    /** The scan's photos with their ARCore poses and the scan box. */
    fun fromScan(photos: PhotoSet, target: File): Zipped {
        BufferedOutputStream(target.outputStream()).use { PhotoSetWriter.write(photos.keyframes, photos.box, it) }
        return Zipped(target, photos.keyframes.size, 0)
    }

    private fun addPhoto(resolver: ContentResolver, uri: Uri, zip: ZipOutputStream, name: String): Boolean {
        try {
            val input = resolver.openInputStream(uri)?.let { BufferedInputStream(it) } ?: return false
            input.use { stream ->
                stream.mark(4)
                val head = ByteArray(3)
                val read = stream.read(head)
                stream.reset()
                if (read == 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) {
                    zip.putNextEntry(ZipEntry(name))
                    stream.copyTo(zip)
                    zip.closeEntry()
                    return true
                }
            }
            val bitmap = decode(resolver, uri) ?: return false
            try {
                zip.putNextEntry(ZipEntry(name))
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, zip)
                zip.closeEntry()
                return ok
            } finally {
                bitmap.recycle()
            }
        } catch (e: IOException) {
            return false
        } catch (e: SecurityException) {
            return false
        } catch (e: RuntimeException) {
            return false // a format the phone can't decode
        }
    }

    /** A picture in any format the phone knows, upright, no larger than a phone can comfortably hold. */
    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > LARGEST) decoder.setTargetSampleSize((longest + LARGEST - 1) / LARGEST)
            }
        } else {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }

    /** A small picture of the first photo that can be read, for the model's thumbnail; null when none can. */
    fun thumbnail(resolver: ContentResolver, uris: List<Uri>, size: Int = 320): Bitmap? {
        for (uri in uris) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0) continue
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
                val picture = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                if (picture != null) return picture
            } catch (e: IOException) {
                continue
            } catch (e: SecurityException) {
                continue
            } catch (e: RuntimeException) {
                continue // a picture the phone can't decode
            }
        }
        return null
    }

    private const val LARGEST = 4096
}
