package com.ma7moud.reality3d.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.media.ExifInterface
import android.os.Looper
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.MainActivity
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.Services
import com.ma7moud.reality3d.ai.AiState
import com.ma7moud.reality3d.ai.CaptureMode
import com.ma7moud.reality3d.ai.ObjectAi
import com.ma7moud.reality3d.ai.ObjectInsight
import com.ma7moud.reality3d.ai.ShapeHint
import com.ma7moud.reality3d.depth.DepthEngine
import com.ma7moud.reality3d.depth.DepthMap
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.mesh.GlbTestKit
import com.ma7moud.reality3d.mesh.GlbWriter
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.project.ProjectKind
import com.ma7moud.reality3d.remote.BuildMode
import com.ma7moud.reality3d.remote.BuildQuality
import com.ma7moud.reality3d.remote.PhotoBuildOptions
import com.ma7moud.reality3d.remote.RemoteEngine
import com.ma7moud.reality3d.remote.RemoteException
import com.ma7moud.reality3d.remote.RemoteServer
import com.ma7moud.reality3d.remote.RemoteSettings
import com.ma7moud.reality3d.remote.ServerInfo
import com.ma7moud.reality3d.preview.ArPreview
import com.ma7moud.reality3d.preview.ArPreviewFactory
import com.ma7moud.reality3d.preview.ArPreviewStatus
import com.ma7moud.reality3d.preview.PreviewModel
import com.ma7moud.reality3d.scan.ScanSupport
import com.ma7moud.reality3d.segmentation.CutOut
import com.ma7moud.reality3d.segmentation.SegmentAnythingModel
import com.ma7moud.reality3d.segmentation.Segmentation
import com.ma7moud.reality3d.segmentation.Subject
import com.ma7moud.reality3d.segmentation.SubjectMask
import com.ma7moud.reality3d.segmentation.SubjectSegmenterEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.math.hypot

/** Fake engines: a dome of depth, a round subject, a canned Gemini Nano answer and a made-up scan. */
class TestReality3DApplication : Reality3DApplication() {
    override fun createServices(): Services {
        val sam = FakeSegmentAnything()
        return Services(
            FakeDepth(), CutOut(FakeSegmenter(), FakeSam(), sam, diagnostics), FakeAi(), FakeScanner, projectStore(), FakeArPreview,
            FakeRemote, sam, useGlViewer = false,
        )
    }
}

/** Segment Anything's files: missing until downloaded. */
internal class FakeSegmentAnything : SegmentAnythingModel {
    override var isReady = false
    override val downloadBytes = 97_249_760L
    override suspend fun download(onProgress: (Float) -> Unit) {
        onProgress(0.5f)
        isReady = true
    }
}

/** Stands in for Segment Anything: finds one round object on the left, and a round object wherever the user taps. */
private class FakeSam : SubjectSegmenterEngine {
    override val canPick = true

    override suspend fun segment(photo: Bitmap, onProgress: (String, Float?) -> Unit) =
        Segmentation(photo.width, photo.height, null, listOf(disc(photo.width * 0.3f, photo.height * 0.5f, photo.height * 0.2f, photo.width, photo.height)))

    override suspend fun objectAt(photo: Bitmap, u: Float, v: Float) =
        disc(u * photo.width, v * photo.height, photo.height * 0.15f, photo.width, photo.height)
}

/**
 * FileProvider keeps its folders in a static map, but Robolectric gives each test a new data folder
 * while keeping statics, so sharing in a later test would point at an earlier test's folder. The same
 * goes for the view model factory, which keeps the first test's Application (and so its services,
 * saved models and settings) for every later view model.
 */
internal fun forgetFileProviderFolders() {
    val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as HashMap<*, *>
    synchronized(cache) { cache.clear() }
    ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null)
}

private class FakeDepth : DepthEngine {
    override val isModelReady = true
    override val downloadBytes = 1L
    override suspend fun downloadModel(onProgress: (Float) -> Unit) = Unit
    override suspend fun estimate(photo: Bitmap, subject: SubjectMask?) =
        DepthMap(64, 64, FloatArray(64 * 64) { (1f - hypot(it % 64 - 32f, it / 64 - 32f) / 45f).coerceIn(0f, 1f) })
}

/** Two round objects: a big one left of centre and a small one on the right. */
private class FakeSegmenter : SubjectSegmenterEngine {
    override suspend fun segment(photo: Bitmap, onProgress: (String, Float?) -> Unit): Segmentation {
        val w = photo.width
        val h = photo.height
        val big = disc(w * 0.375f, h * 0.5f, h * 0.33f, w, h)
        val small = disc(w * 0.8f, h * 0.5f, h * 0.17f, w, h)
        val foreground = SubjectMask(w, h, FloatArray(w * h) { i -> maxOf(big.at(i % w, i / w), small.at(i % w, i / w)) })
        return Segmentation(w, h, foreground, listOf(big, small))
    }
}

/** A round object of radius [r] around ([cx], [cy]) on a [w] × [h] photo. */
private fun disc(cx: Float, cy: Float, r: Float, w: Int, h: Int): Subject {
    val left = (cx - r).toInt().coerceAtLeast(0)
    val top = (cy - r).toInt().coerceAtLeast(0)
    val width = minOf(w - left, (2 * r).toInt() + 2)
    val height = minOf(h - top, (2 * r).toInt() + 2)
    return Subject(left, top, width, height, FloatArray(width * height) { i ->
        if (hypot(left + i % width - cx, top + i / width - cy) < r) 1f else 0f
    })
}

/** Stands in for ARCore's AR view: always available, placed as soon as it resumes. */
internal object FakeArPreview : ArPreviewFactory {
    var last: Preview? = null

    override fun check(activity: Activity, userRequestedInstall: Boolean) = ScanSupport.Ready

    override fun create(context: Context, model: PreviewModel): ArPreview = Preview(context, model).also { last = it }

    class Preview(context: Context, val model: PreviewModel) : ArPreview {
        private val _status = MutableStateFlow(ArPreviewStatus())
        override val status: StateFlow<ArPreviewStatus> = _status
        override val view: View = View(context)
        var closed = false

        override fun resume() {
            _status.value = ArPreviewStatus(phase = ArPreviewStatus.Phase.PLACED, scale = 2f)
        }

        override fun pause() = Unit

        override fun resetScale() {
            _status.value = _status.value.copy(scale = 1f)
        }

        override fun placeAgain() {
            _status.value = _status.value.copy(phase = ArPreviewStatus.Phase.READY_TO_PLACE)
        }

        override fun close() {
            closed = true
        }
    }
}

/** Stands in for the user's computer: an SF3D server that returns a small closed model, and a photo builder. */
internal object FakeRemote : RemoteServer {
    var uploads = 0
    var photoBuilds = 0
    var photosReady = true
    var lastOptions: PhotoBuildOptions? = null
    var lastPhotos: Map<String, ByteArray> = emptyMap()

    /** When set, the photo builder waits for it, so a test can look at (or cancel) a build in progress. */
    var hold: CompletableDeferred<Unit>? = null
    var photoFailure: String? = null

    private val tetra = Mesh3D(
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), FloatArray(12), null,
        intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3), solid = true, subjectIsolated = true,
    )

    fun reset() {
        photoBuilds = 0
        photosReady = true
        lastOptions = null
        lastPhotos = emptyMap()
        hold = null
        photoFailure = null
    }

    override suspend fun health(settings: RemoteSettings) = ServerInfo(
        "Reality3D server", "1.1", authRequired = false,
        engines = listOf(
            RemoteEngine("preview", "Quick preview (CPU)", "", true),
            RemoteEngine("sf3d", "Stable Fast 3D", "Fast and textured", true),
            RemoteEngine("photogrammetry", "Photos → 3D", "", photosReady, kind = "photos", why = if (photosReady) null else "Run  pip install pycolmap"),
        ),
    )

    override suspend fun generate(settings: RemoteSettings, png: ByteArray, onProgress: (Float?, String?) -> Unit): ByteArray {
        uploads++
        onProgress(0.5f, "Generating the shape")
        return GlbWriter.write(tetra, texture = null)
    }

    override suspend fun buildFromPhotos(settings: RemoteSettings, photos: File, options: PhotoBuildOptions, onProgress: (Float?, String?) -> Unit): ByteArray {
        photoBuilds++
        lastOptions = options
        lastPhotos = ZipFile(photos).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() } }
        onProgress(0.5f, "Building the surface from the photos")
        hold?.await()
        photoFailure?.let { throw RemoteException(it) }
        val count = lastPhotos.keys.count { it.startsWith("images/") }
        // A scan's photos come with poses, so the model comes back in real meters.
        val known = "cameras.json" in lastPhotos
        return GlbTestKit.withBuildNote(GlbWriter.write(tetra, texture = null, longestSideMeters = 0.2f), scaleKnown = known, photos = count, placed = count)
    }
}

private class FakeAi : ObjectAi {
    override val state: StateFlow<AiState> = MutableStateFlow(AiState.Ready)
    override fun refresh() = Unit
    override fun download() = Unit
    override suspend fun describe(photo: Bitmap) = ObjectInsight(
        name = "Test mug", shape = ShapeHint.ROUND, thicknessPercent = 90, reflective = true, holes = true,
        recommendedMode = CaptureMode.SCAN_360, confidence = 0.8f, advice = listOf("Use soft light"),
    )
}

/** The longest side of a binary STL's bounding box. */
internal fun stlLongestSideMm(bytes: ByteArray): Float {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val count = buffer.getInt(80)
    val min = FloatArray(3) { Float.POSITIVE_INFINITY }
    val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
    for (t in 0 until count) {
        for (v in 0 until 3) {
            for (axis in 0 until 3) {
                val value = buffer.getFloat(84 + t * 50 + 12 + v * 12 + axis * 4)
                min[axis] = minOf(min[axis], value)
                max[axis] = maxOf(max[axis], value)
            }
        }
    }
    return (0 until 3).maxOf { max[it] - min[it] }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestReality3DApplication::class)
class Reality3DSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = forgetFileProviderFolders()

    private fun waitForText(text: String, substring: Boolean = false) =
        waitFor { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    /** Waits for [condition], letting delayed main-thread work (like the rebuild debounce) run. */
    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            condition()
        }
    }

    @Test
    fun photoBecomesAModelThatCanBeReshapedAndShared() {
        compose.onNodeWithText("Start 360° scan").assertIsDisplayed()
        compose.onNodeWithText("Take photo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gemini Nano: ").assertExists()
        val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
        compose.runOnUiThread { viewModel.setPhoto(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)) }
        waitFor { viewModel.state.value.subjects != null && viewModel.state.value.progress == null }
        assertEquals(2, viewModel.state.value.subjects!!.count)

        compose.onNodeWithText("Make 3D model").performScrollTo().performClick()
        waitFor { viewModel.state.value.mesh != null }
        val mesh = viewModel.state.value.mesh!!
        assertTrue(mesh.solid)
        assertTrue(mesh.subjectIsolated)
        compose.onNodeWithText("3D preview").assertExists()

        // Viewer tools: looks, measuring (no GL in tests, so no points) and the photo comparison.
        compose.onNodeWithText("Wireframe").performScrollTo().performClick()
        compose.onNodeWithText("Measure").performScrollTo().performClick()
        compose.onNodeWithText("Tap the first point on the model.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Compare with photo").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Photo and model compared").assertExists()
        compose.onNodeWithText("Tap the first point on the model.").assertDoesNotExist()

        // A measured length sets the real size, which the exports follow.
        compose.runOnUiThread { viewModel.setRealLength(0.5f, 0.05f) }
        assertEquals(0.1f, viewModel.metersPerUnit(mesh), 1e-6f)
        val stl = runBlocking { viewModel.export(ExportFormat.STL)!! }
        assertEquals(mesh.longestSide * 0.1f * 1000f, stlLongestSideMm(stl.bytes), 0.01f)

        compose.onNodeWithText("Relief").performScrollTo().performClick()
        waitFor { viewModel.state.value.mesh?.solid == false }

        compose.onNodeWithText("Analyse object").performScrollTo().performClick()
        waitFor { viewModel.state.value.insight != null }
        compose.onNodeWithText("Test mug").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("80% sure").assertIsDisplayed()
        compose.onNodeWithText("Shiny").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tip: Use soft light").performScrollTo().assertIsDisplayed()
        assertEquals(0.9f, viewModel.state.value.settings.thickness, 1e-6f)

        compose.onNodeWithText("Share").performScrollTo().performClick()
        waitFor { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals("model/gltf-binary", send!!.type)
        assertFalse(viewModel.state.value.isError)
    }

    @Test
    fun theComputersAiMakesAFullModel() {
        val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
        compose.runOnUiThread { viewModel.setPhoto(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)) }
        waitFor { viewModel.state.value.subjects != null && viewModel.state.value.progress == null }
        compose.onNodeWithText("Connect to your computer").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Address, e.g. 192.168.1.20:8765")).performTextInput("8.8.8.8:8765")
        compose.onNodeWithText("Save and connect").performClick()
        // Plain http to the internet is refused.
        waitForText("Plain http only works for servers on your own network. Use https:// for anything else.")

        compose.onNodeWithText("Connect to your computer").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Address, e.g. 192.168.1.20:8765")).performTextInput("192.168.1.20:8765")
        compose.onNodeWithText("Save and connect").performClick()
        waitForText("Connected to 192.168.1.20:8765")
        // The first AI engine is chosen over the CPU preview.
        assertEquals("sf3d", viewModel.state.value.remote.settings.engine)
        compose.onNodeWithText("Make full 3D model").performScrollTo().performClick()
        waitFor { viewModel.state.value.remote.model != null }
        assertEquals(1, FakeRemote.uploads)
        compose.onNodeWithText("Full 3D model · Stable Fast 3D").performScrollTo().assertIsDisplayed()
        val model = viewModel.state.value.remote.model!!
        assertEquals(4, model.mesh.triangleCount)
        // Without a photo model the AI model is given the default size, 20 cm on its longest side.
        assertEquals(0.2f, viewModel.aiMetersPerUnit(model.mesh) * model.mesh.longestSide, 1e-5f)

        val glb = runBlocking { viewModel.exportAi(ExportFormat.GLB)!! }
        assertTrue(glb.fileName.endsWith("_ai.glb"))
        compose.onNodeWithText("Save to My models").performScrollTo().performClick()
        waitFor { viewModel.state.value.remote.saved === model }
        assertFalse(viewModel.state.value.isError)
    }

    @Test
    fun savedModelsReopenFromTheGallery() {
        val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
        compose.runOnUiThread { viewModel.setPhoto(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)) }
        waitFor { viewModel.state.value.subjects != null && viewModel.state.value.progress == null }
        // The photo is rated as soon as its objects are found: a blank photo has no detail at all.
        compose.onNodeWithText("Photo quality").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("• The photo looks blurry. Hold the phone steady and tap the object to focus.").assertExists()
        compose.onNodeWithText("Make 3D model").performScrollTo().performClick()
        waitFor { viewModel.state.value.mesh != null && viewModel.state.value.progress == null }

        compose.onNodeWithText("Save to My models").performScrollTo().performClick()
        waitFor { viewModel.state.value.savedMesh != null }
        compose.onNodeWithText("Saved to My models").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("See all (1)").performScrollTo().performClick()

        waitForText("My models")
        val name = viewModel.state.value.message!!.substringAfter("“").substringBefore("”")
        compose.onNodeWithText(name).assertIsDisplayed().performClick()
        waitForText("Rename")
        compose.onNodeWithText("3D preview").assertExists()
        compose.onNodeWithText("From a photo", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Share").performScrollTo().performClick()
        waitFor { shadowOf(compose.activity).peekNextStartedActivity() != null }
        @Suppress("DEPRECATION")
        val send = shadowOf(compose.activity).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals("model/gltf-binary", send!!.type)

        // The saved model stands in the room at its real size.
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.CAMERA)
        compose.onNodeWithText("View in AR").performScrollTo().performClick()
        waitForText("Drag to turn it, pinch to resize it, tap elsewhere to move it.")
        val ar = FakeArPreview.last!!
        assertEquals(name, ar.model.name)
        compose.onNodeWithText("2.0× real size", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Real size").performClick()
        compose.onNodeWithText("Real size · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Place again").performClick()
        waitForText("Tap where the model should stand.")
        compose.onNodeWithText("Close").performClick()
        waitForText("Rename")
        assertTrue(ar.closed)

        compose.onNodeWithText("Rename").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(name)).performTextReplacement("Kitchen mug")
        compose.onNodeWithText("Save").performClick()
        waitForText("Kitchen mug")

        compose.onNodeWithText("Delete this model").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        waitForText("No saved models yet. Make one from a photo or a 360° scan, then tap Save to My models.")
    }

    @Test
    fun tappingAnObjectModelsOnlyItAndTheOutlineCanBePainted() {
        val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
        compose.runOnUiThread { viewModel.setPhoto(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)) }
        waitFor { viewModel.state.value.subjects != null && viewModel.state.value.progress == null }
        compose.onNodeWithText("2 objects found. Tap one to model only it.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Make 3D model").performScrollTo().performClick()
        waitFor { viewModel.state.value.mesh != null && viewModel.state.value.progress == null }
        val both = viewModel.state.value.mesh!!
        assertTrue(both.uvs!!.filterIndexed { i, _ -> i % 2 == 0 }.min() < 0.3f)

        // Tap the small object on the right: the model is remade from it alone.
        compose.onNodeWithContentDescription("Selected photo").performScrollTo().performTouchInput {
            click(Offset(width * 0.8f, height * 0.5f))
        }
        waitFor { viewModel.state.value.subjects?.selection == setOf(1) && viewModel.state.value.mesh !== both && viewModel.state.value.progress == null }
        val small = viewModel.state.value.mesh!!
        assertTrue(small.subjectIsolated)
        assertTrue(small.uvs!!.filterIndexed { i, _ -> i % 2 == 0 }.min() > 0.6f)
        compose.onNodeWithText("1 of 2 objects chosen. Tap to add or remove.").performScrollTo().assertIsDisplayed()

        // Erase a stripe through it in the editor.
        compose.onNodeWithText("Edit outline").performScrollTo().performClick()
        waitFor { viewModel.editor.value != null }
        compose.onNodeWithText("Erase").performClick()
        compose.onNodeWithContentDescription("Outline editor").performTouchInput {
            swipe(Offset(width * 0.8f, 0f), Offset(width * 0.8f, height.toFloat()), durationMillis = 300)
        }
        compose.onNodeWithText("Undo").assertIsEnabled()
        compose.onNodeWithText("Done").performClick()
        waitFor { viewModel.state.value.subjects?.edited == true && viewModel.state.value.mesh !== small && viewModel.state.value.progress == null }
        assertTrue(viewModel.editor.value == null)
        compose.onNodeWithText("Using your edited outline; the dimmed part is left out.").performScrollTo().assertIsDisplayed()
        assertFalse(viewModel.state.value.isError)

        // Choosing all objects again keeps the painted stroke.
        compose.onNodeWithText("Use all").performScrollTo().performClick()
        waitFor { viewModel.state.value.subjects?.selection?.isEmpty() == true && viewModel.state.value.progress == null }
        assertTrue(viewModel.state.value.subjects!!.edited)
    }

    /**
     * The way a phone's photo comes in: a large JPEG through a content URI, decoded, turned and scaled for real.
     * Robolectric can't run ImageDecoder, so this goes through the decoder older phones use.
     */
    @Test
    @Config(sdk = [27])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aCameraPhotoOpensUprightAndShowsItsObjects() {
        val activity = compose.activity
        // Cameras store portrait photos sideways, with an EXIF note to turn them.
        val file = File(activity.cacheDir, "camera/capture_test.jpg").apply { parentFile!!.mkdirs() }
        val sideways = Bitmap.createBitmap(3000, 2250, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 205, 210))
            Canvas(this).drawCircle(1500f, 1125f, 600f, Paint().apply { color = Color.rgb(40, 60, 120) })
        }
        file.outputStream().use { sideways.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val viewModel = ViewModelProvider(activity)[Reality3DViewModel::class.java]
        compose.runOnUiThread { viewModel.loadPhoto(uri) }
        waitFor { viewModel.state.value.photo != null && viewModel.state.value.progress == null }
        assertFalse(viewModel.state.value.message, viewModel.state.value.isError)
        waitFor { viewModel.state.value.subjects != null }

        val photo = viewModel.state.value.photo!!
        assertEquals(1200, photo.width)
        assertEquals(1600, photo.height)
        assertEquals(2, viewModel.state.value.subjects!!.count)
        assertNotNull(viewModel.state.value.photoQuality)
        assertFalse(viewModel.state.value.message, viewModel.state.value.isError)
        compose.onNodeWithContentDescription("Selected photo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Photo quality").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Make 3D model").performScrollTo().performClick()
        waitFor { viewModel.state.value.mesh != null && viewModel.state.value.progress == null }
        assertFalse(viewModel.state.value.message, viewModel.state.value.isError)
    }

    private fun fakeJpegs(count: Int): List<Uri> = List(count) { i ->
        // The first bytes of a JPEG are all the app looks at when the photo goes into the zip as it is.
        Uri.fromFile(File(compose.activity.cacheDir, "pick$i.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), i.toByte(), 7, 7)) })
    }

    private fun connectComputer(viewModel: Reality3DViewModel) {
        compose.runOnUiThread { viewModel.saveRemoteSettings("192.168.1.20:8765", "") }
        waitFor { viewModel.state.value.remote.server != null }
    }

    @Test
    fun manyPhotosBecomeAModelOnTheComputerAndArePutInMyModels() {
        FakeRemote.reset()
        val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
        compose.onNodeWithText("3D from many photos").performScrollTo().assertIsDisplayed()
        // Nothing to build with yet: the button is there, and off.
        compose.onNodeWithText("Connect your computer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Build the model on my computer").performScrollTo().assertIsNotEnabled()
        connectComputer(viewModel)
        waitForText("Connected to 192.168.1.20:8765. The photo builder is ready.")

        compose.runOnUiThread { viewModel.pickPhotos(fakeJpegs(8)) }
        waitForText("8 photos")
        compose.onNodeWithText("One object").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("High").performScrollTo().performClick()
        compose.onNodeWithText("Whole scene").performScrollTo().performClick()
        assertEquals(PhotoBuildOptions(BuildQuality.HIGH, BuildMode.SCENE), viewModel.state.value.photoBuild.options)
        compose.onNodeWithText("Build the model on my computer").performScrollTo().assertIsEnabled().performClick()

        waitFor { viewModel.state.value.photoBuild.savedId != null }
        assertFalse(viewModel.state.value.photoBuild.status, viewModel.state.value.photoBuild.statusIsError)
        assertEquals(1, FakeRemote.photoBuilds)
        assertEquals(PhotoBuildOptions(BuildQuality.HIGH, BuildMode.SCENE), FakeRemote.lastOptions)
        // The photos went as they were: a zip of eight images, no poses.
        assertEquals((0 until 8).map { "images/photo_00$it.jpg" }, FakeRemote.lastPhotos.keys.sorted())
        assertTrue(FakeRemote.lastPhotos.getValue("images/photo_003.jpg").contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 3, 7, 7)))
        assertTrue(viewModel.state.value.photoBuild.picked.isEmpty())
        compose.onNodeWithText("Saved to My models as", substring = true).performScrollTo().assertIsDisplayed()

        val app = ApplicationProvider.getApplicationContext<Application>() as Reality3DApplication
        val saved = app.services.projects.refresh().single()
        assertEquals(ProjectKind.PHOTOGRAMMETRY, saved.kind)
        assertEquals(viewModel.state.value.photoBuild.savedId, saved.id)
        // No poses came with the photos, so the size isn't known.
        assertFalse(saved.sizeKnown)
        assertNotNull(saved.quality)
        assertTrue(saved.quality!!.issues.any { it.startsWith("Only 8 photos.") })

        compose.onNodeWithText("Open").performScrollTo().performClick()
        waitForText("From many photos", substring = true)
    }

    @Test
    fun theHomeScreenSaysWhenTheComputerCantBuildFromPhotosYet() {
        FakeRemote.reset()
        FakeRemote.photosReady = false
        try {
            val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
            connectComputer(viewModel)
            compose.runOnUiThread { viewModel.pickPhotos(fakeJpegs(8)) }
            waitForText("Connected, but the photo builder isn't ready: Run  pip install pycolmap")
            compose.onNodeWithText("Build the model on my computer").performScrollTo().assertIsNotEnabled()
        } finally {
            FakeRemote.reset()
        }
    }

    @Test
    fun aBuildCanBeCancelledAndFewPhotosAreNotEnough() {
        FakeRemote.reset()
        val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
        connectComputer(viewModel)
        compose.runOnUiThread { viewModel.pickPhotos(fakeJpegs(3)) }
        waitForText("At least 6 photos are needed; 40 or more work best.")
        compose.onNodeWithText("Build the model on my computer").performScrollTo().assertIsNotEnabled()

        compose.runOnUiThread { viewModel.pickPhotos(fakeJpegs(9)) }
        FakeRemote.hold = CompletableDeferred()
        compose.runOnUiThread { viewModel.buildFromPhotos() }
        waitForText("Building the surface from the photos")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        waitFor { viewModel.state.value.photoBuild.progress == null }
        assertEquals(null, viewModel.state.value.photoBuild.savedId)
        FakeRemote.reset()
    }

    @Test
    fun aBuildThatFailsSaysWhyAndKeepsThePhotosPicked() {
        FakeRemote.reset()
        FakeRemote.photoFailure = "The photos didn't show enough detail."
        try {
            val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
            connectComputer(viewModel)
            compose.runOnUiThread { viewModel.pickPhotos(fakeJpegs(7)) }
            compose.runOnUiThread { viewModel.buildFromPhotos() }
            waitFor { viewModel.state.value.photoBuild.statusIsError }
            assertEquals("The photos didn't show enough detail.", viewModel.state.value.photoBuild.status)
            assertEquals(7, viewModel.state.value.photoBuild.picked.size)
            compose.onNodeWithText("The photos didn't show enough detail.").performScrollTo().assertIsDisplayed()
        } finally {
            FakeRemote.reset()
        }
    }

    @Test
    fun aGlbFromAnotherAppOpensAndIsSavedToMyModels() {
        val viewModel = ViewModelProvider(compose.activity)[Reality3DViewModel::class.java]
        val square = Mesh3D(
            floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f), FloatArray(12), null,
            intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3), solid = true, subjectIsolated = true,
        )
        val file = File(compose.activity.cacheDir, "Chair from Scaniverse.glb").apply { writeBytes(GlbWriter.write(square, texture = null, longestSideMeters = 2f)) }
        compose.runOnUiThread { viewModel.importModel(Uri.fromFile(file)) }
        waitFor { viewModel.state.value.photoBuild.savedId != null }
        assertFalse(viewModel.state.value.photoBuild.status, viewModel.state.value.photoBuild.statusIsError)
        val app = ApplicationProvider.getApplicationContext<Application>() as Reality3DApplication
        val saved = app.services.projects.refresh().single()
        assertEquals(ProjectKind.IMPORTED, saved.kind)
        assertEquals("Chair from Scaniverse", saved.name)
        assertEquals(4, saved.triangles)
        // Two meters across, taken at its word but not claimed to be measured.
        assertFalse(saved.sizeKnown)
        assertEquals(2f, saved.size.max(), 1e-3f)

        // A file that isn't a model says so and leaves My models alone.
        val notAModel = File(compose.activity.cacheDir, "notes.glb").apply { writeBytes(ByteArray(200)) }
        compose.runOnUiThread { viewModel.importModel(Uri.fromFile(notAModel)) }
        waitFor { viewModel.state.value.photoBuild.statusIsError }
        assertTrue(viewModel.state.value.photoBuild.status!!.startsWith("Couldn't open that file:"))
        assertEquals(1, app.services.projects.refresh().size)
    }
}
