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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.MainActivity
import com.ma7moud.reality3d.scan.CoachTip
import com.ma7moud.reality3d.scan.CoverageTracker
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.project.ProjectKind
import com.ma7moud.reality3d.remote.RemoteSettings
import com.ma7moud.reality3d.remote.RemoteSettingsStore
import com.ma7moud.reality3d.scan.Keyframe
import com.ma7moud.reality3d.scan.PhotoSet
import com.ma7moud.reality3d.scan.ScanBox
import com.ma7moud.reality3d.scan.ScanCapture
import com.ma7moud.reality3d.scan.ScanEngine
import com.ma7moud.reality3d.scan.ScanEngineFactory
import com.ma7moud.reality3d.scan.ScanPhase
import com.ma7moud.reality3d.scan.ScanQuality
import com.ma7moud.reality3d.scan.ScanReconstructor
import com.ma7moud.reality3d.scan.ScanStatus
import com.ma7moud.reality3d.scan.ScanSupport
import com.ma7moud.reality3d.scan.SyntheticScan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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
        // The first lap done and a little of the second.
        val coverage = BooleanArray(CoverageTracker.CELLS) { it < 15 }
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

    var continued = 0

    override fun continueScanning() {
        continued++
        startScanning()
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
        val coverage = _status.value.coverage
        ScanCapture(mesh, keyframes, ScanQuality.assess(coverage, keyframes.size, 73, 0.7f))
    }

    override suspend fun photoSet(): PhotoSet {
        val poses = SyntheticScan.ringPoses().take(6)
        return PhotoSet(poses.map { Keyframe(ByteArray(64) { i -> i.toByte() }, 320, 240, SyntheticScan.colorIntrinsics, it) }, ScanBox(0.1f, 0.8f, -0.3f, 0.4f, 0.796f))
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
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        // The how-to card has been read, except in the test that looks at it.
        ScanPreferences(app).apply {
            tipsSeen = true
            voice = true
        }
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
        compose.onNodeWithText("Object size").assertIsDisplayed()
        compose.onNodeWithText("Step 3 of 4 · Set the size").assertIsDisplayed()
        compose.onNodeWithText("Start scan").performClick()

        waitForText("Covered 60%")
        compose.onNodeWithText("24 photos · 73 depth maps").assertIsDisplayed()
        // The first lap is done and the second has begun; the instruction follows a moment later, once it has held.
        compose.onNodeWithText("Lap 2 of 2 · 3 of 12 sides").assertIsDisplayed()
        waitForText("Now hold the phone a bit higher, looking down at 45°, and go around again.")
        compose.onNodeWithText("Step 4 of 4 · Walk around it").assertIsDisplayed()
        // The coach takes over when the engine has advice.
        compose.runOnUiThread {
            engine.emit(
                ScanStatus(
                    phase = ScanPhase.SCANNING, coverageFraction = 0.6f, photos = 24, depthFrames = 73,
                    coach = CoachTip("Too fast. Move the phone slowly.", CoachTip.Kind.WARNING),
                ),
            )
        }
        waitForText("Too fast. Move the phone slowly.")
        compose.runOnUiThread { engine.startScanning() }
        compose.onNodeWithText("Build model").assertIsEnabled().performClick()

        waitForText("Your scan")
        // The camera stays ready to add views to this scan.
        assertTrue(!engine.closed)
        compose.onNodeWithText("Scan quality").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No view from straight above, so the top may be rough.", substring = true).assertExists()
        compose.onNodeWithText("Add more views to this scan").performScrollTo().performClick()
        waitForText("Covered 60%")
        assertEquals(1, engine.continued)
        compose.onNodeWithText("Build model").assertIsEnabled().performClick()
        waitForText("Your scan")
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
        assertTrue(engine.closed)
    }

    @Test
    fun theScanReportCanBeCopiedWhileScanningAndAfterwards() {
        compose.onNodeWithText("Start 360° scan").performClick()
        waitForText("Start scan")
        compose.onNodeWithText("Start scan").performClick()
        waitForText("Covered 60%")
        val clipboard = ApplicationProvider.getApplicationContext<Application>().getSystemService(android.content.ClipboardManager::class.java)

        compose.onNodeWithText("Copy report").performClick()
        compose.onNodeWithText("Report copied").assertIsDisplayed()
        val duringScan = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(duringScan, duringScan.startsWith("Reality3D "))
        assertTrue(duringScan, duringScan.contains("Timeline"))
        assertTrue(duringScan, duringScan.contains("scanning"))
        assertTrue(duringScan, duringScan.contains("60% covered, 24 photos, 73 depth maps"))

        compose.onNodeWithText("Build model").performClick()
        waitForText("Your scan")
        compose.onNodeWithText("Copy report").performScrollTo().performClick()
        val afterwards = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(afterwards, afterwards.contains("Built in "))
        assertTrue(afterwards, afterwards.contains("quality "))
    }

    @Test
    fun theHowToCardShowsTheFirstTimeAndFromTheTipsButton() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ScanPreferences(app).tipsSeen = false
        compose.onNodeWithText("Start 360° scan").performClick()
        waitForText("How to scan")
        compose.onNodeWithText("Walk slowly around it", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("How to scan").assertDoesNotExist()
        assertTrue(ScanPreferences(app).tipsSeen)
        // It doesn't come back by itself, but the Tips button opens it again.
        compose.onNodeWithText("Tips").performClick()
        compose.onNodeWithText("How to scan").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("How to scan").assertDoesNotExist()
    }

    @Test
    fun theSizeIsChosenFromPicturesOfObjectsAndTheVoiceCanBeTurnedOff() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithText("Start 360° scan").performClick()
        waitForText("Start scan")
        compose.onNodeWithText("Medium · shoebox").assertIsDisplayed()
        compose.onNodeWithText("40 cm").assertIsDisplayed()
        compose.onNodeWithText("Small · in a hand").performClick()
        compose.onNodeWithText("20 cm").assertIsDisplayed()
        compose.onNodeWithText("Large · a chair").performClick()
        compose.onNodeWithText("80 cm").assertIsDisplayed()

        compose.onNodeWithText("Voice: on").performClick()
        compose.onNodeWithText("Voice: off").assertIsDisplayed()
        assertEquals(false, ScanPreferences(app).voice)
        compose.onNodeWithText("Voice: off").performClick()
        compose.onNodeWithText("Voice: on").assertIsDisplayed()
        assertEquals(true, ScanPreferences(app).voice)
    }

    @Test
    fun theScanTellsWhenItIsEnoughToBuild() {
        compose.onNodeWithText("Start 360° scan").performClick()
        waitForText("Start scan")
        val engine = FakeScanner.engine!!
        compose.onNodeWithText("Start scan").performClick()
        waitForText("Covered 60%")

        // Nine of twelve sides of both main laps: enough, whatever the top and the high lap look like.
        val twoLaps = BooleanArray(CoverageTracker.CELLS) { it % CoverageTracker.SEGMENTS < 9 && it < 2 * CoverageTracker.SEGMENTS }
        compose.runOnUiThread {
            engine.emit(ScanStatus(phase = ScanPhase.SCANNING, coverage = twoLaps, coverageFraction = 0.49f, photos = 30, depthFrames = 50))
        }
        waitForText("Enough to build")
        compose.onNodeWithText("Build model").assertIsEnabled()
    }

    @Test
    fun leavingOrStartingOverAsksFirstOnceThereIsSomethingToLose() {
        compose.onNodeWithText("Start 360° scan").performClick()
        waitForText("Start scan")
        val engine = FakeScanner.engine!!
        compose.onNodeWithText("Start scan").performClick()
        waitForText("Covered 60%")

        compose.onNodeWithText("Restart").performClick()
        compose.onNodeWithText("Start over?").assertIsDisplayed()
        compose.onNodeWithText("Keep scanning").performClick()
        compose.onNodeWithText("Start over?").assertDoesNotExist()
        compose.onNodeWithText("Covered 60%").assertIsDisplayed()

        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Leave the scan?").assertIsDisplayed()
        compose.onNodeWithText("Keep scanning").performClick()
        compose.onNodeWithText("Covered 60%").assertIsDisplayed()

        // Starting over goes back to placing the box.
        compose.onNodeWithText("Restart").performClick()
        compose.onNodeWithText("Start over").performClick()
        waitForText("Start scan")

        // A scan that has hardly begun is dropped without asking.
        compose.onNodeWithText("Start scan").performClick()
        waitForText("Covered 60%")
        compose.runOnUiThread { engine.emit(ScanStatus(phase = ScanPhase.SCANNING, coverageFraction = 0.05f, depthFrames = 1)) }
        waitForText("Covered 5%")
        compose.onNodeWithText("Close").performClick()
        waitForText("Start 360° scan")
        assertTrue(engine.closed)
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

    private fun startScanning() {
        compose.onNodeWithText("Start 360° scan").performClick()
        waitForText("Start scan")
        compose.onNodeWithText("Start scan").performClick()
        waitForText("Covered 60%")
    }

    @Test
    fun theScansPhotosBecomeAModelOnTheComputerWithItsRealSize() {
        FakeRemote.reset()
        startScanning()
        compose.onNodeWithText("40 or more photos give a cleaner model.").assertExists()
        compose.onNodeWithText("Build on my computer · best quality").assertIsEnabled().performClick()

        // The computer isn't known yet: the app asks for its address, then goes on by itself.
        waitForText("Your computer")
        compose.onNode(hasSetTextAction() and hasText("Address, e.g. 192.168.1.20:8765")).performTextInput("192.168.1.20:8765")
        compose.onNodeWithText("Save and connect").performClick()
        waitForText("Model from your photos")

        assertEquals(1, FakeRemote.photoBuilds)
        val photos = FakeRemote.lastPhotos
        assertEquals(6, photos.keys.count { it.startsWith("images/") })
        val cameras = String(photos.getValue("cameras.json"))
        assertTrue(cameras, cameras.contains("\"box\": {\"center\": [0.100000, 1.000000, -0.300000], \"size\": 0.400000, \"floorY\": 0.796000}"))
        // Built from 6 photos with poses: real size, the model is 20 cm.
        compose.onNodeWithText("Size: ", substring = true).performScrollTo().assertIsDisplayed()
        val sizeText = compose.onNodeWithText("Size: ", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertFalse(sizeText, sizeText.contains("≈"))
        assertTrue(sizeText, sizeText.contains("20.0 × 20.0 × 20.0 cm"))
        compose.onNodeWithText("Built from 6 photos on your computer.", substring = true).assertExists()

        compose.onNodeWithText("Save to My models").performScrollTo().performClick()
        waitForText("Saved to My models")
        val app = ApplicationProvider.getApplicationContext<Application>() as Reality3DApplication
        val saved = app.services.projects.refresh().single()
        assertEquals(ProjectKind.PHOTOGRAMMETRY, saved.kind)
        assertTrue(saved.sizeKnown)
        assertTrue(saved.name.startsWith("Photo model "))
    }

    @Test
    fun buildingOnTheComputerCanBeCancelledAndAFailureBringsTheCameraBack() {
        FakeRemote.reset()
        val app = ApplicationProvider.getApplicationContext<Application>()
        RemoteSettingsStore(app).save(RemoteSettings(url = "http://192.168.1.20:8765"))
        startScanning()

        FakeRemote.hold = CompletableDeferred()
        compose.onNodeWithText("Build on my computer · best quality").performClick()
        waitForText("Building on your computer")
        compose.onNodeWithText("This takes a few minutes.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        // Back at the camera with the scan as it was.
        waitForText("Covered 60%")
        assertEquals(1, FakeRemote.photoBuilds)

        FakeRemote.hold = null
        FakeRemote.photoFailure = "The photos didn't show enough detail."
        compose.onNodeWithText("Build on my computer · best quality").performClick()
        waitForText("The photos didn't show enough detail.")
        compose.onNodeWithText("Covered 60%").assertIsDisplayed()
        assertEquals(2, FakeRemote.photoBuilds)
        FakeRemote.reset()
    }

    @Test
    fun theComputerBuildNeedsEnoughPhotosFirst() {
        startScanning()
        val engine = FakeScanner.engine!!
        compose.runOnUiThread { engine.emit(ScanStatus(phase = ScanPhase.SCANNING, coverageFraction = 0.2f, photos = 8, depthFrames = 5)) }
        waitForText("Covered 20%")
        compose.onNodeWithText("Build on my computer · best quality").assertIsNotEnabled()
        compose.onNodeWithText("Needs at least 12 photos: keep going around.").assertExists()
        compose.runOnUiThread { engine.emit(ScanStatus(phase = ScanPhase.SCANNING, coverageFraction = 0.5f, photos = 45, depthFrames = 40)) }
        waitForText("Covered 50%")
        compose.onNodeWithText("Build on my computer · best quality").assertIsEnabled()
        compose.onNodeWithText("40 or more photos give a cleaner model.").assertDoesNotExist()
    }
}
