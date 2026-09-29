package com.ma7moud.reality3d.segmentation

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.diagnostics.Diagnostics
import com.ma7moud.reality3d.diagnostics.ExitKind
import com.ma7moud.reality3d.diagnostics.ExitRecord
import com.ma7moud.reality3d.diagnostics.Fallback
import com.ma7moud.reality3d.diagnostics.Step
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CutOutTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photo = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    /** The next launch after a run that died inside [step]. */
    private fun afterCrashIn(step: Step): Diagnostics {
        Diagnostics(context) { emptyList() }.begin(step)
        return Diagnostics(context) { listOf(ExitRecord(System.currentTimeMillis() + 1, ExitKind.NATIVE_CRASH, true, "crash")) }
    }

    private class Engine(val name: String, override val canPick: Boolean = false) : SubjectSegmenterEngine {
        val calls = ArrayList<String>()

        override suspend fun segment(photo: Bitmap, onProgress: (String, Float?) -> Unit): Segmentation? {
            calls += "$name.segment"
            return Segmentation(photo.width, photo.height, null, emptyList())
        }

        override suspend fun objectAt(photo: Bitmap, u: Float, v: Float): Subject? {
            calls += "$name.objectAt"
            return Subject(0, 0, 1, 1, floatArrayOf(1f))
        }
    }

    private class Model(override var isReady: Boolean) : SegmentAnythingModel {
        override val downloadBytes = 1L
        override suspend fun download(onProgress: (Float) -> Unit) {
            isReady = true
        }
    }

    @Test
    fun mlKitIsNotAskedAgainOnceItHasCrashedTheApp() = runBlocking {
        val diagnostics = afterCrashIn(Step.FIND_OBJECTS)
        assertTrue(diagnostics.isOff(Fallback.ONE_OBJECT))
        // The real one answers before it touches ML Kit at all.
        assertNull(MlKitSubjectMasker(context, diagnostics).segment(photo) { _, _ -> })
    }

    @Test
    fun photosGoToMlKitUntilItCrashesAndThenToSegmentAnything() = runBlocking {
        val mlKit = Engine("mlkit")
        val sam = Engine("sam", canPick = true)
        val model = Model(isReady = true)

        // A phone where nothing has crashed: ML Kit, whether or not Segment Anything is there.
        val fresh = CutOut(mlKit, sam, model, Diagnostics(context) { emptyList() })
        assertFalse(fresh.canPick)
        fresh.segment(photo) { _, _ -> }
        assertNull(fresh.objectAt(photo, 0.5f, 0.5f))
        assertEquals(listOf("mlkit.segment"), mlKit.calls + sam.calls)

        // ML Kit crashed the app: Segment Anything, and taps find objects.
        mlKit.calls.clear()
        val crashed = CutOut(mlKit, sam, model, afterCrashIn(Step.FIND_OBJECTS))
        assertTrue(crashed.canPick)
        crashed.segment(photo) { _, _ -> }
        assertNotNull(crashed.objectAt(photo, 0.5f, 0.5f))
        assertEquals(listOf("sam.segment", "sam.objectAt"), sam.calls)
        assertTrue(mlKit.calls.isEmpty())
    }

    @Test
    fun onAPhoneKnownToCrashSegmentAnythingIsUsedWithoutAnyCrashFirst() = runBlocking {
        val mlKit = Engine("mlkit")
        val sam = Engine("sam", canPick = true)
        val blocked = Diagnostics(context, model = "SM-S948B") { emptyList() }
        val cut = CutOut(mlKit, sam, Model(isReady = true), blocked)
        assertTrue(cut.canPick)
        cut.segment(photo) { _, _ -> }
        assertEquals(listOf("sam.segment"), sam.calls)
        assertTrue(mlKit.calls.isEmpty())
        // Before Segment Anything is downloaded ML Kit's own slot answers "nothing", without touching ML Kit.
        assertNull(MlKitSubjectMasker(context, blocked).segment(photo) { _, _ -> })
    }

    @Test
    fun withoutTheDownloadOrAfterItCrashesTooTheOldWayIsKept() = runBlocking {
        val mlKit = Engine("mlkit")
        val sam = Engine("sam", canPick = true)
        val crashed = afterCrashIn(Step.FIND_OBJECTS)
        // Not downloaded yet: ML Kit's slot (which answers "nothing" once it has crashed the app).
        val missing = CutOut(mlKit, sam, Model(isReady = false), crashed)
        assertFalse(missing.canPick)
        missing.segment(photo) { _, _ -> }
        assertEquals(listOf("mlkit.segment"), mlKit.calls)
        assertTrue(sam.calls.isEmpty())

        // Segment Anything crashed the app too: it is off, and the cut-out is not tried with it again.
        Diagnostics(context) { emptyList() }.begin(Step.SAM_CPU)
        val both = Diagnostics(context) { listOf(ExitRecord(System.currentTimeMillis() + 2, ExitKind.NATIVE_CRASH, true, "crash")) }
        assertTrue(both.isOff(Fallback.NO_SAM))
        val off = CutOut(mlKit, sam, Model(isReady = true), both)
        assertFalse(off.canPick)
        mlKit.calls.clear()
        off.segment(photo) { _, _ -> }
        assertEquals(listOf("mlkit.segment"), mlKit.calls)
        assertTrue(sam.calls.isEmpty())
    }
}
