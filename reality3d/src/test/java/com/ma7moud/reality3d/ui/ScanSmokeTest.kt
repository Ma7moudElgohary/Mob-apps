package com.ma7moud.reality3d.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.MainActivity
import com.ma7moud.reality3d.scan.CoverageTracker
import com.ma7moud.reality3d.scan.Keyframe
import com.ma7moud.reality3d.scan.ScanCapture
import com.ma7moud.reality3d.scan.ScanEngine
import com.ma7moud.reality3d.scan.ScanEngineFactory
import com.ma7moud.reality3d.scan.ScanPhase
import com.ma7moud.reality3d.scan.ScanReconstructor
import com.ma7moud.reality3d.scan.ScanStatus
import com.ma7moud.reality3d.scan.ScanSupport
import com.ma7moud.reality3d.scan.SyntheticScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Stands in for ARCore: always available, and builds the made-up scan of a ball and a block. */
internal object FakeScanner : ScanEngineFactory {
    var engine: FakeScanEngine? = null

    override fun check(activity: Activity, userRequestedInstall: Boolean) = ScanSupport.Ready

    override fun create(context: Context): ScanEngine = FakeScanEngine().also { engine = it }
}

internal class FakeScanEngine : ScanEngine {
    private val _status = MutableStateFlow(ScanStatus(phase = ScanPhase.READY))
    override val status: StateFlow<ScanStatus> = _status
    var resumes = 0
    var closed = false

    override fun createView(context: Context): View = View(context)

    override fun setBoxSize(meters: Float) {
        _status.value = ScanStatus(phase = _status.value.phase, boxSize = meters)
    }

    override fun startScanning() {
        val coverage = BooleanArray(CoverageTracker.CELLS) { it < 22 }
        _status.value = ScanStatus(
            phase = ScanPhase.SCANNING,
            coverage = coverage,
            coverageFraction = 0.6f,
            nextStep = CoverageTracker.Step.MIDDLE_RING,
            phoneAzimuth = 40f,
            phoneRing = 1,
            photos = 24,
            depthFrames = 73,
        )
    }

    override fun restart() {
        _status.value = ScanStatus(phase = ScanPhase.READY)
    }

    fun emit(status: ScanStatus) {
        _status.value = status
    }

    override fun resume() {
        resumes++
    }

    override fun pause() = Unit

    override suspend fun build(progress: (String) -> Unit): ScanCapture = withContext(Dispatchers.Default) {
        progress("Building the surface…")
        val poses = SyntheticScan.ringPoses()
        val mesh = ScanReconstructor.reconstruct(SyntheticScan.fuse(poses, resolution = 48), poses.take(6).map { SyntheticScan.photo(it) })
        val keyframes = poses.take(6).map { Keyframe(ByteArray(64) { i -> i.toByte() }, 320, 240, SyntheticScan.colorIntrinsics, it) }
        ScanCapture(mesh, keyframes)
    }

    override fun close() {
        closed = true
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestReality3DApplication::class)
class ScanSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        forgetFileProviderFolders()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.CAMERA)
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            condition()
        }
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun scanGuidesTheUserAndGivesAMeasuredModelWithItsPhotos() {
        compose.onNodeWithText("Start 360° scan").assertIsDisplayed().performClick()

        // ARCore is ready and the box is placed: choose its size and start.
        waitForText("Start scan")
        val engine = FakeScanner.engine!!
        assertTrue(engine.resumes > 0)
        compose.onNodeWithText("Box size").assertIsDisplayed()
        compose.onNodeWithText("Start scan").performClick()

        waitForText("Covered 60%")
        compose.onNodeWithText("24 photos · 73 depth maps").assertIsDisplayed()
        compose.onNodeWithText("Now hold the phone higher and go around again, looking down at 45°.").assertIsDisplayed()
        compose.onNodeWithText("Build model").assertIsEnabled().performClick()

        waitForText("Your scan")
        assertTrue(engine.closed)
        compose.onNodeWithText("3D preview").assertExists()
        // The ball (14 cm across) and the block next to it: 20 cm wide, 14 cm deep, 14 cm high.
        compose.onNodeWithText("Size: ", substring = true).performScrollTo().assertIsDisplayed()
        val sizeText = compose.onNodeWithText("Size: ", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val numbers = Regex("""(\d+\.\d)""").findAll(sizeText).map { it.value.toFloat() }.toList()
        assertEquals(3, numbers.size)
        assertEquals(20f, numbers[0], 1f)
        assertEquals(14f, numbers[1], 1f)
        assertEquals(14f, numbers[2], 1f)

        // The photos go out as a zip for photogrammetry on a computer.
        compose.onNodeWithText("Photos").performScrollTo().performClick()
        compose.onNodeWithText("Share").performScrollTo().performClick()
        waitFor { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals("application/zip", send!!.type)
        assertTrue(send.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith("_photos.zip"))

        compose.onNodeWithText("Done").performScrollTo().performClick()
        waitForText("Start 360° scan")
    }

    @Test
    fun buildWaitsUntilTheObjectHasBeenCircled() {
        compose.onNodeWithText("Start 360° scan").performClick()
        waitForText("Start scan")
        val engine = FakeScanner.engine!!
        compose.onNodeWithText("Start scan").performClick()
        waitForText("Covered 60%")
        // A scan that has only just begun can't be built yet.
        compose.runOnUiThread { engine.emit(ScanStatus(phase = ScanPhase.SCANNING, coverageFraction = 0.1f, depthFrames = 3)) }
        waitForText("Go around at least once before building.")
        compose.onNodeWithText("Build model").assertIsNotEnabled()
        compose.onNodeWithText("Close").performClick()
        waitForText("Start 360° scan")
        assertTrue(engine.closed)
    }
}
