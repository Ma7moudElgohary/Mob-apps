package com.ma7moud.reality3d.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.MainActivity
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.Services
import com.ma7moud.reality3d.ai.AiState
import com.ma7moud.reality3d.ai.ObjectAi
import com.ma7moud.reality3d.ai.ObjectInsight
import com.ma7moud.reality3d.ai.ShapeHint
import com.ma7moud.reality3d.depth.DepthEngine
import com.ma7moud.reality3d.depth.DepthMap
import com.ma7moud.reality3d.segmentation.SubjectMask
import com.ma7moud.reality3d.segmentation.SubjectSegmenterEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.time.Duration
import kotlin.math.hypot

/** Fake engines: a dome of depth, a round subject, a canned Gemini Nano answer and a made-up scan. */
class TestReality3DApplication : Reality3DApplication() {
    override fun createServices() = Services(FakeDepth(), FakeSegmenter(), FakeAi(), FakeScanner, useGlViewer = false)
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
    override suspend fun estimate(photo: Bitmap) =
        DepthMap(64, 64, FloatArray(64 * 64) { (1f - hypot(it % 64 - 32f, it / 64 - 32f) / 45f).coerceIn(0f, 1f) })
}

private class FakeSegmenter : SubjectSegmenterEngine {
    override suspend fun segment(photo: Bitmap, onProgress: (String, Float?) -> Unit): SubjectMask {
        val w = photo.width
        val h = photo.height
        return SubjectMask(w, h, FloatArray(w * h) { if (hypot(it % w - w / 2f, it / w - h / 2f) < h * 0.4f) 1f else 0f })
    }
}

private class FakeAi : ObjectAi {
    override val state: StateFlow<AiState> = MutableStateFlow(AiState.Ready)
    override fun refresh() = Unit
    override fun download() = Unit
    override suspend fun describe(photo: Bitmap) = ObjectInsight("Test mug", ShapeHint.ROUND, 90, "Use soft light")
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestReality3DApplication::class)
class Reality3DSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = forgetFileProviderFolders()

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

        compose.onNodeWithText("Make 3D model").performScrollTo().performClick()
        waitFor { viewModel.state.value.mesh != null }
        val mesh = viewModel.state.value.mesh!!
        assertTrue(mesh.solid)
        assertTrue(mesh.subjectIsolated)
        compose.onNodeWithText("3D preview").assertExists()

        compose.onNodeWithText("Relief").performScrollTo().performClick()
        waitFor { viewModel.state.value.mesh?.solid == false }

        compose.onNodeWithText("Recognise object").performScrollTo().performClick()
        waitFor { viewModel.state.value.insight != null }
        compose.onNodeWithText("Test mug").performScrollTo().assertIsDisplayed()
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
}
