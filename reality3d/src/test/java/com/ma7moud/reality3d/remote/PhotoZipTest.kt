package com.ma7moud.reality3d.remote

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.scan.Intrinsics
import com.ma7moud.reality3d.scan.Keyframe
import com.ma7moud.reality3d.scan.PhotoSet
import com.ma7moud.reality3d.scan.ScanBox
import com.ma7moud.reality3d.scan.SyntheticScan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** Robolectric can't run ImageDecoder (Android 9+), so the conversion goes through the decoder older phones use. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [27])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoZipTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun encoded(format: Bitmap.CompressFormat, width: Int = 64, height: Int = 48): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFCC6633.toInt()) }.compress(format, 90, out)
        return out.toByteArray()
    }

    private fun file(name: String, bytes: ByteArray) = Uri.fromFile(File(folder.root, name).apply { writeBytes(bytes) })

    private fun entries(zip: File): Map<String, ByteArray> = ZipFile(zip).use { z -> z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes() } }

    @Test
    fun jpegsGoInExactlyAsTheCameraMadeThemAndOtherFormatsBecomeJpegs() {
        val jpeg = encoded(Bitmap.CompressFormat.JPEG)
        val png = encoded(Bitmap.CompressFormat.PNG)
        val target = File(folder.root, "photos.zip")
        val steps = ArrayList<Pair<Int, Int>>()
        val zipped = PhotoZip.fromUris(
            context, listOf(file("a.jpg", jpeg), file("b.png", png), file("broken.jpg", byteArrayOf(1, 2, 3)), Uri.fromFile(File(folder.root, "missing.jpg")), file("c.jpg", jpeg + byteArrayOf(0))),
            target,
        ) { done, total -> steps += done to total }
        assertEquals(3, zipped.photos)
        assertEquals(2, zipped.skipped)
        val content = entries(target)
        assertEquals(listOf("images/photo_000.jpg", "images/photo_001.jpg", "images/photo_002.jpg"), content.keys.sorted())
        assertArrayEquals(jpeg, content["images/photo_000.jpg"])
        assertArrayEquals(jpeg + byteArrayOf(0), content["images/photo_002.jpg"])
        // The PNG was turned into a JPEG.
        val converted = content["images/photo_001.jpg"]!!
        assertEquals(0xFF.toByte(), converted[0])
        assertEquals(0xD8.toByte(), converted[1])
        assertEquals(5 to 5, steps.last())
    }

    @Test
    fun noMoreThanTheBuilderTakesAreSent() {
        val jpeg = encoded(Bitmap.CompressFormat.JPEG, 8, 8)
        val uri = file("p.jpg", jpeg)
        val zipped = PhotoZip.fromUris(context, List(PhotoZip.MAX_PHOTOS + 5) { uri }, File(folder.root, "many.zip"))
        assertEquals(PhotoZip.MAX_PHOTOS, zipped.photos)
        assertEquals(5, zipped.skipped)
    }

    @Test
    fun aScanGoesOutWithItsPosesAndTheBox() {
        val intrinsics = Intrinsics(1400f, 1400f, 960f, 540f, 1920, 1080)
        val keyframes = List(4) { Keyframe(ByteArray(20) { i -> (it + i).toByte() }, 1920, 1080, intrinsics, SyntheticScan.lookAt(0.3f * it, 0.3f, 0.5f, 0f, 0f, 0f)) }
        val target = File(folder.root, "scan.zip")
        val zipped = PhotoZip.fromScan(PhotoSet(keyframes, ScanBox(0f, 0f, 0f, 0.4f, 0f)), target)
        assertEquals(4, zipped.photos)
        val content = entries(target)
        assertTrue(content.keys.containsAll(listOf("images/frame_000.jpg", "images/frame_003.jpg", "cameras.json")))
        assertTrue(String(content["cameras.json"]!!).contains("\"box\""))
    }

    @Test
    fun theThumbnailIsTheFirstPhotoThatCanBeRead() {
        val jpeg = encoded(Bitmap.CompressFormat.JPEG, 800, 600)
        val thumbnail = PhotoZip.thumbnail(context.contentResolver, listOf(file("junk.jpg", byteArrayOf(9, 9)), Uri.fromFile(File(folder.root, "gone.jpg")), file("ok.jpg", jpeg)))
        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.width in 200..400)
        assertNull(PhotoZip.thumbnail(context.contentResolver, listOf(file("junk2.jpg", byteArrayOf(9, 9)))))
    }
}
