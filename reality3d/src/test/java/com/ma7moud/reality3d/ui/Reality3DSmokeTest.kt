package com.ma7moud.reality3d.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import com.ma7moud.reality3d.preview.ArPreview
import com.ma7moud.reality3d.preview.ArPreviewFactory
import com.ma7moud.reality3d.preview.ArPreviewStatus
import com.ma7moud.reality3d.preview.PreviewModel
import com.ma7moud.reality3d.scan.ScanSupport
import com.ma7moud.reality3d.segmentation.Segmentation
import com.ma7moud.reality3d.segmentation.Subject
import com.ma7moud.reality3d.segmentation.SubjectMask
import com.ma7moud.reality3d.segmentation.SubjectSegmenterEngine
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.hypot

/** Fake engines: a dome of depth, a round subject, a canned Gemini Nano answer and a made-up scan. */
class TestReality3DApplication : Reality3DApplication() {
    override fun createServices() = Services(FakeDepth(), FakeSegmenter(), FakeAi(), FakeScanner, projectStore(), FakeArPreview, useGlViewer = false)
}

/**
 * FileProvider keeps its folders in a static map, but Robolectric gives each test a new data folder
 * while keeping statics, so sharing in a later test would point at an earlier test's folder.
 */
internal fun forgetFileProviderFolders() {
    val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as HashMap<*, *>
    synchronized(cache) { cache.clear() }
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

    private fun disc(cx: Float, cy: Float, r: Float, w: Int, h: Int): Subject {
        val left = (cx - r).toInt().coerceAtLeast(0)
        val top = (cy - r).toInt().coerceAtLeast(0)
        val width = minOf(w - left, (2 * r).toInt() + 2)
        val height = minOf(h - top, (2 * r).toInt() + 2)
        return Subject(left, top, width, height, FloatArray(width * height) { i ->
            if (hypot(left + i % width - cx, top + i / width - cy) < r) 1f else 0f
        })
    }
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

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

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
}
