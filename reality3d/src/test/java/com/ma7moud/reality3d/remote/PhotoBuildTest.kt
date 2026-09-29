package com.ma7moud.reality3d.remote

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.mesh.GlbTestKit
import com.ma7moud.reality3d.mesh.GlbWriter
import com.ma7moud.reality3d.mesh.Mesh3D
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoBuildTest {

    private fun mesh(size: Float) = Mesh3D(
        floatArrayOf(0f, 0f, 0f, size, 0f, 0f, 0f, size, 0f, 0f, 0f, size), FloatArray(12), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
        intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3), solid = true, subjectIsolated = true,
    )

    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    @Test
    fun aModelFromAScanIsInRealMetersAndOneFromGalleryPhotosIsNot() {
        val glb = GlbWriter.write(mesh(1f), GlbWriter.Texture(jpeg(64, 48), "image/jpeg"), longestSideMeters = 1f)
        val measured = PhotoModelBuilder.open(GlbTestKit.withBuildNote(glb, scaleKnown = true, photos = 40, placed = 37))
        assertTrue(measured.sizeKnown)
        assertEquals(1f, measured.metersPerUnit, 0f)
        assertEquals(40, measured.photos)
        assertEquals(37, measured.placed)
        assertNotNull(measured.texture)
        assertEquals(64, measured.texture!!.width)

        // One unit long, size unknown: taken as meters, but not claimed to be right.
        val guessed = PhotoModelBuilder.open(GlbTestKit.withBuildNote(glb, scaleKnown = false, photos = 30, placed = 30))
        assertTrue(!guessed.sizeKnown)
        assertEquals(1f, guessed.metersPerUnit, 0f)
    }

    @Test
    fun aFileFromAnotherAppKeepsItsUnitsWhenTheyAreBelievableAndIsShrunkToAHandfulOfCentimetersWhenNot() {
        val plain = { size: Float -> GlbWriter.write(mesh(size), texture = null, longestSideMeters = size) }
        val room = PhotoModelBuilder.open(plain(4f))
        assertEquals(1f, room.metersPerUnit, 0f)
        assertTrue(!room.sizeKnown)
        assertNull(room.photos)
        assertNull(room.texture)
        // 500 "units" across is millimeters or a game engine's scale: not a believable building.
        val huge = PhotoModelBuilder.open(plain(500f))
        assertEquals(0.2f, huge.metersPerUnit * huge.mesh.longestSide, 1e-4f)
        // Millimeters as meters would be a speck of dust: also not believable.
        val tiny = PhotoModelBuilder.open(plain(0.002f))
        assertEquals(0.2f, tiny.metersPerUnit * tiny.mesh.longestSide, 1e-4f)
    }

    @Test
    fun aTextureTooBigForAPhoneIsShrunk() {
        val glb = GlbWriter.write(mesh(1f), GlbWriter.Texture(jpeg(9000, 40), "image/jpeg"), longestSideMeters = 1f)
        val texture = PhotoModelBuilder.open(glb).texture!!
        assertTrue("${texture.width}", texture.width <= PhotoModelBuilder.MAX_TEXTURE)
        assertTrue(texture.width >= PhotoModelBuilder.MAX_TEXTURE / 2)
    }

    @Test
    fun aFileThatIsNotAModelIsRefusedInPlainWords() {
        val error = assertThrows(RemoteException::class.java) { PhotoModelBuilder.open(ByteArray(100)) }
        assertTrue(error.message, error.message!!.startsWith("The computer sent a model this app can't open"))
    }

    @Test
    fun theSetOfPhotosIsRatedByHowManyWereUsedAndHowManyThereWere() {
        fun model(placed: Int?) = PhotoModel(mesh(1f), null, 1f, true, photos = 40, placed = placed)
        val good = model(40).quality(40)
        assertEquals(100, good.score)
        assertTrue(good.issues.isEmpty())
        val lost = model(20).quality(40)
        assertTrue(lost.score in 30..60)
        assertTrue(lost.issues.first(), lost.issues.first().startsWith("Only 20 of 40 photos could be placed."))
        val few = model(12).quality(12)
        assertTrue(few.score < 70)
        assertTrue(few.issues.single().startsWith("Only 12 photos."))
        // When the file doesn't say, all the photos are taken as used.
        assertEquals(100, model(null).quality(40).score)
    }

    private class Recorder(private val model: ByteArray = ByteArray(0)) : RemoteServer {
        var sent: File? = null
        var engines = listOf(RemoteEngine("photogrammetry", "Photos → 3D", "", true, kind = "photos"))

        override suspend fun health(settings: RemoteSettings) = ServerInfo("Reality3D server", "1.1", false, engines)
        override suspend fun generate(settings: RemoteSettings, png: ByteArray, onProgress: (Float?, String?) -> Unit) = model
        override suspend fun buildFromPhotos(settings: RemoteSettings, photos: File, options: PhotoBuildOptions, onProgress: (Float?, String?) -> Unit): ByteArray {
            sent = photos
            onProgress(0.4f, "Building the surface from the photos")
            return model
        }
    }

    @Test
    fun theServerIsCheckedForAPhotoBuilderBeforeAnyPhotosAreSent() = runBlocking {
        val server = Recorder()
        val builder = PhotoModelBuilder(server)
        assertEquals("photogrammetry", builder.checkServer(RemoteSettings("192.168.1.2")).id)
        server.engines = listOf(RemoteEngine("sf3d", "Stable Fast 3D", "", true))
        val old = assertThrows(RemoteException::class.java) { runBlocking { builder.checkServer(RemoteSettings("192.168.1.2")) } }
        assertTrue(old.message, old.message!!.contains("no photo builder"))
        server.engines = listOf(RemoteEngine("photogrammetry", "Photos → 3D", "", false, kind = "photos", why = "Run  pip install pycolmap"))
        val missing = assertThrows(RemoteException::class.java) { runBlocking { builder.checkServer(RemoteSettings("192.168.1.2")) } }
        assertEquals("Run  pip install pycolmap", missing.message)
        Unit
    }

    @Test
    fun theBuilderHandsOverTheZipAndOpensWhatComesBack() = runBlocking {
        val glb = GlbTestKit.withBuildNote(GlbWriter.write(mesh(0.3f), texture = null, longestSideMeters = 0.3f), scaleKnown = true, photos = 9, placed = 9)
        val server = Recorder(glb)
        val steps = ArrayList<Pair<Float?, String?>>()
        val zip = File.createTempFile("photos", ".zip")
        val model = PhotoModelBuilder(server).build(RemoteSettings("192.168.1.2"), zip, PhotoBuildOptions()) { f, m -> steps += f to m }
        assertEquals(zip, server.sent)
        assertTrue(model.sizeKnown)
        assertEquals(4, model.mesh.triangleCount)
        assertEquals(0.4f to "Building the surface from the photos", steps.first())
        assertEquals(1f to "Opening the model", steps.last())
    }
}
