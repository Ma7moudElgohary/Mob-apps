package com.ma7moud.reality3d.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.MainActivity
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.diagnostics.Diagnostics
import com.ma7moud.reality3d.diagnostics.Fallback
import com.ma7moud.reality3d.diagnostics.Step
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestReality3DApplication::class)
class CrashReportSmokeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() = forgetFileProviderFolders()

    @Test
    fun theLastCrashIsReportedAndItsFeatureTurnedOff() {
        val app = ApplicationProvider.getApplicationContext<Reality3DApplication>()
        // The last run died finding the photo's objects, with an error the crash handler wrote down.
        Diagnostics(app) { emptyList() }.apply {
            begin(Step.FIND_OBJECTS)
            recordJavaCrash(Thread.currentThread(), IllegalStateException("boom"))
        }

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
            compose.onNodeWithText("picking objects off after a crash").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Turn on").performScrollTo().performClick()
            compose.onNodeWithText("picking objects off after a crash").assertDoesNotExist()
            assertFalse(app.diagnostics.isOff(Fallback.ONE_OBJECT))
        }
    }
}
