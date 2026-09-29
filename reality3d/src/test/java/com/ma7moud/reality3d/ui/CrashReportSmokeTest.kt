package com.ma7moud.reality3d.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.MainActivity
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.data.LastPhoto
import com.ma7moud.reality3d.diagnostics.Diagnostics
import com.ma7moud.reality3d.diagnostics.Fallback
import com.ma7moud.reality3d.diagnostics.Step
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
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestReality3DApplication::class)
class CrashReportSmokeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() = forgetFileProviderFolders()

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            condition()
        }
    }

    /** The last run died with a Java error while ML Kit was finding the photo's objects. */
    private fun mlKitCrashedLastTime(app: Reality3DApplication) = Diagnostics(app) { emptyList() }.apply {
        begin(Step.FIND_OBJECTS)
        recordJavaCrash(Thread.currentThread(), IllegalStateException("boom"))
    }

    private fun keptPhotoFolder(app: Reality3DApplication) = File(app.filesDir, "last_photo")

    @Test
    fun theLastPhotoComesBackAfterACrashAndIsNotAnalysedUntilAsked() {
        val app = ApplicationProvider.getApplicationContext<Reality3DApplication>()
        // The previous run kept the photo it was opening, then died with it.
        LastPhoto(keptPhotoFolder(app)).save(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888))
        mlKitCrashedLastTime(app)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: Reality3DViewModel
            scenario.onActivity { viewModel = ViewModelProvider(it)[Reality3DViewModel::class.java] }
            waitFor { viewModel.state.value.photo != null }
            assertEquals(320, viewModel.state.value.photo!!.width)
            assertEquals(240, viewModel.state.value.photo!!.height)
            // Nothing runs on it until the user asks, since it may be what brought the app down.
            assertEquals(null, viewModel.state.value.subjects)
            compose.onNodeWithText("Reality3D closed unexpectedly last time").assertIsDisplayed()
            compose.onNodeWithText("Your last photo is back. Tap Make 3D model, or Edit outline to cut the object out yourself.")
                .performScrollTo().assertIsDisplayed()

            compose.onNodeWithText("Make 3D model").performScrollTo().performClick()
            waitFor { viewModel.state.value.mesh != null && viewModel.state.value.progress == null }
            assertFalse(viewModel.state.value.message, viewModel.state.value.isError)
        }
    }

    @Test
    fun aPhotoIsKeptFromTheMomentItOpensAndDroppedAfterANormalExit() {
        val app = ApplicationProvider.getApplicationContext<Reality3DApplication>()
        val kept = LastPhoto(keptPhotoFolder(app))
        // An old photo from an earlier session that ended normally is dropped at launch.
        kept.save(Bitmap.createBitmap(50, 40, Bitmap.Config.ARGB_8888))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor { kept.load() == null }
            lateinit var viewModel: Reality3DViewModel
            scenario.onActivity { viewModel = ViewModelProvider(it)[Reality3DViewModel::class.java] }
            assertEquals(null, viewModel.state.value.photo)

            // A photo that opens is on disk before anything is run on it.
            compose.runOnUiThread { viewModel.setPhoto(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)) }
            waitFor { kept.load() != null }
            assertEquals(320, kept.load()!!.width)
            assertNotNull(viewModel.state.value.photo)
        }
    }

    @Test
    fun theLastCrashIsReportedAndItsFeatureTurnedOff() {
        val app = ApplicationProvider.getApplicationContext<Reality3DApplication>()
        mlKitCrashedLastTime(app)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Reality3D closed unexpectedly last time").assertIsDisplayed()
            compose.onNodeWithText(
                "It crashed with an error in the app. It was finding the objects in the photo (ML Kit). " +
                    Fallback.ONE_OBJECT.label + " Try again.",
            ).assertIsDisplayed()
            compose.onNodeWithText("Copy report").performClick()
            val clip = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clip.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied, copied.contains("java.lang.IllegalStateException: boom"))
            assertTrue(copied.contains("Turned off: ONE_OBJECT"))
            compose.onNodeWithText("Copied. Paste it into your message.").assertIsDisplayed()

            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Reality3D closed unexpectedly last time").assertDoesNotExist()
            compose.onNodeWithText("Google's cut-out off after a crash").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Turn on").performScrollTo().performClick()
            compose.onNodeWithText("Google's cut-out off after a crash").assertDoesNotExist()
            assertFalse(app.diagnostics.isOff(Fallback.ONE_OBJECT))
        }
    }

    @Test
    fun segmentAnythingTakesOverWhereMlKitCrashed() {
        val app = ApplicationProvider.getApplicationContext<Reality3DApplication>()
        mlKitCrashedLastTime(app)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Pick objects with Segment Anything").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Get Segment Anything (97 MB, once)").performScrollTo().performClick()
            waitFor { compose.onAllNodesWithText("Pick objects with Segment Anything").fetchSemanticsNodes().isEmpty() }

            // Photos are now cut out by Segment Anything, and a tap on something else adds it.
            lateinit var viewModel: Reality3DViewModel
            scenario.onActivity { viewModel = ViewModelProvider(it)[Reality3DViewModel::class.java] }
            compose.runOnUiThread { viewModel.setPhoto(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)) }
            waitFor { viewModel.state.value.subjects != null && viewModel.state.value.progress == null }
            assertEquals(1, viewModel.state.value.subjects!!.count)
            assertTrue(viewModel.state.value.subjects!!.canPick)
            compose.onNodeWithText("Object found; the dimmed part is left out. Tap anything else to add it.").performScrollTo().assertIsDisplayed()

            compose.onNodeWithContentDescription("Selected photo").performScrollTo().performTouchInput {
                click(Offset(width * 0.8f, height * 0.5f))
            }
            waitFor { viewModel.state.value.subjects?.count == 2 && viewModel.state.value.progress == null }
            compose.onNodeWithText("2 objects found. Tap one to model only it.").performScrollTo().assertIsDisplayed()
            assertFalse(viewModel.state.value.isError)
        }
    }
}
