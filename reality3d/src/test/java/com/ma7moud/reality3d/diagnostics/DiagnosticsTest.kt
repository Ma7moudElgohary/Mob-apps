package com.ma7moud.reality3d.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DiagnosticsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Leaves [step] written down, as a run killed inside it does. */
    private fun diedDuring(step: Step) = Diagnostics(context) { emptyList() }.begin(step)

    private var clock = 0L

    /** An exit record from [ageMs] ago, each one later than the one before. */
    private fun exit(kind: ExitKind, ageMs: Long = 0, foreground: Boolean = true, trace: ByteArray? = null): ExitRecord {
        clock = maxOf(clock + 1, System.currentTimeMillis())
        return ExitRecord(clock - ageMs, kind, foreground, "crash", trace = { trace })
    }

    @Test
    fun aNativeCrashInsideAStepTurnsThatFeatureOffAndIsReportedOnce() {
        diedDuring(Step.FIND_OBJECTS)
        val records = listOf(exit(ExitKind.NATIVE_CRASH))
        val diagnostics = Diagnostics(context) { records }

        val report = diagnostics.report!!
        assertTrue(report.summary, report.summary.startsWith("It crashed in native code. It was finding the objects in the photo (ML Kit)."))
        assertTrue(report.summary.endsWith(Fallback.ONE_OBJECT.label + " Try again."))
        assertEquals(listOf(Fallback.ONE_OBJECT), report.turnedOff)
        assertTrue(diagnostics.isOff(Fallback.ONE_OBJECT))
        assertFalse(diagnostics.isOff(Fallback.NO_SAM))

        // The next launch has nothing new to say, and the feature stays off until turned back on.
        val next = Diagnostics(context) { records }
        assertNull(next.report)
        assertEquals(listOf(Fallback.ONE_OBJECT), next.turnedOff)
        next.turnAllBackOn()
        assertFalse(next.isOff(Fallback.ONE_OBJECT))
    }

    @Test
    fun aStepLeftByANormalExitIsNotACrash() {
        diedDuring(Step.DEPTH_GPU)
        val diagnostics = Diagnostics(context) { listOf(exit(ExitKind.OTHER, foreground = false)) }
        assertNull(diagnostics.report)
        assertFalse(diagnostics.isOff(Fallback.NO_DEPTH_GPU))
        // Running out of memory in the background is normal too; on screen it is reported.
        diedDuring(Step.DEPTH_GPU)
        assertNull(Diagnostics(context) { listOf(exit(ExitKind.KILLED, foreground = false)) }.report)
        diedDuring(Step.DEPTH_CPU)
        val killed = Diagnostics(context) { listOf(exit(ExitKind.KILLED)) }.report!!
        assertTrue(killed.summary, killed.summary.startsWith("Android closed it, most likely because the phone ran out of memory."))
        assertTrue(killed.turnedOff.isEmpty())
    }

    @Test
    fun javaCrashesAreReportedWithTheirStack() {
        Diagnostics(context) { emptyList() }.apply {
            begin(Step.DEPTH_GPU)
            recordJavaCrash(Thread.currentThread(), IllegalStateException("boom"))
        }
        val diagnostics = Diagnostics(context) { emptyList() }
        val report = diagnostics.report!!
        assertTrue(report.details, report.details.contains("Thread: ${Thread.currentThread().name}"))
        assertTrue(report.details.contains("java.lang.IllegalStateException: boom"))
        assertTrue(report.details.contains("Turned off: NO_DEPTH_GPU"))
        assertTrue(diagnostics.isOff(Fallback.NO_DEPTH_GPU))
        diagnostics.dismiss()
        assertNull(diagnostics.report)
    }

    @Test
    fun withoutExitRecordsALeftoverStepCountsAsACrash() {
        diedDuring(Step.SAM_CPU)
        val report = Diagnostics(context) { null }.report!!
        assertEquals(listOf(Fallback.NO_SAM), report.turnedOff)
        assertTrue(report.summary, report.summary.startsWith("It closed while it was busy. It was finding the objects with Segment Anything on the CPU"))
    }

    @Test
    fun anOlderNativeCrashIsPinnedOnItsLibrariesAndDescribed() {
        val tombstone = tombstone(
            crashedFrames = listOf(
                Triple("/vendor/lib64/libOpenCL.so", "", 0L),
                Triple("/data/user_de/0/com.google.android.gms/app_chimera/m/0000/lib/arm64-v8a/libsegmenter.so", "Segmenter::Run", 44L),
            ),
        )
        // No step was written: the crash happened in a version before steps existed.
        val diagnostics = Diagnostics(context) { listOf(exit(ExitKind.NATIVE_CRASH, ageMs = 60_000, trace = tombstone)) }
        val report = diagnostics.report!!
        assertTrue(report.summary, report.summary.startsWith("It crashed in native code (SIGSEGV (SEGV_MAPERR), fault address 0x10)."))
        assertEquals(listOf(Fallback.ONE_OBJECT), report.turnedOff)
        assertTrue(report.details, report.details.contains("Crashed thread: segmenter (4321)"))
        assertTrue(report.details.contains("#00 pc 00001000  /vendor/lib64/libOpenCL.so"))
        assertTrue(report.details.contains("#01 pc 00001001  /data/user_de/0/com.google.android.gms/app_chimera/m/0000/lib/arm64-v8a/libsegmenter.so (Segmenter::Run+44)"))
        assertTrue(report.details.contains("E Reality3D: Subject segmentation failed"))
        assertFalse("debug lines are left out", report.details.contains("ignored"))
        assertFalse("other threads are left out", report.details.contains("libother.so"))
    }

    @Test
    fun depthCrashesArePinnedOnTheirAccelerator() {
        val gpu = tombstone(listOf(Triple("/vendor/lib64/libGLES_mali.so", "", 0L), Triple("/data/app/x/lib/arm64/libLiteRtClGlAccelerator.so", "Run", 8L)))
        assertEquals(listOf(Fallback.NO_DEPTH_GPU), Tombstone.read(gpu)!!.likelyCauses)
        val npu = tombstone(listOf(Triple("/vendor/lib64/libQnnHtp.so", "", 0L), Triple("/data/app/x/lib/arm64/libLiteRtDispatch.so", "Run", 8L)))
        assertEquals(listOf(Fallback.NO_DEPTH_NPU), Tombstone.read(npu)!!.likelyCauses)
        // Only the graphics driver: ML Kit and the depth model both use the GPU.
        val driver = tombstone(listOf(Triple("/vendor/lib64/egl/libGLES_mali.so", "", 0L)))
        assertEquals(listOf(Fallback.ONE_OBJECT, Fallback.NO_DEPTH_GPU), Tombstone.read(driver)!!.likelyCauses)
        val unknown = tombstone(listOf(Triple("/system/lib64/libhwui.so", "SkCanvas::drawImage", 12L)))
        assertTrue(Tombstone.read(unknown)!!.likelyCauses.isEmpty())
        assertNull(Tombstone.read(byteArrayOf(0x0A, 0x7F, 0x01)))
    }

    @Test
    fun anOlderCrashWithoutClearCauseTurnsOffWhatRunsOnEveryPhoto() {
        // A Java crash from before this version leaves no stack; nor does a native one without a tombstone.
        assertEquals(listOf(Fallback.ONE_OBJECT), Diagnostics(context) { listOf(exit(ExitKind.JAVA_CRASH, ageMs = 90_000)) }.report!!.turnedOff)
        val diagnostics = Diagnostics(context) { listOf(exit(ExitKind.NATIVE_CRASH, ageMs = 60_000)) }
        assertEquals(listOf(Fallback.ONE_OBJECT), diagnostics.report!!.turnedOff)
        // The same crash after steps were written down, outside any step, turns nothing off.
        diagnostics.turnAllBackOn()
        assertTrue(Diagnostics(context) { listOf(exit(ExitKind.NATIVE_CRASH)) }.report!!.turnedOff.isEmpty())
    }

    @Test
    fun freezesShowTheMainThread() {
        val traces = """
            ----- pid 123 at 2026-09-28 20:00:00 -----
            Cmd line: com.ma7moud.reality3d

            "main" prio=5 tid=1 Native
              | group="main" sCount=1
              at com.ma7moud.reality3d.Slow.work(Slow.kt:10)

            "Signal Catcher" daemon prio=10 tid=2 Runnable
              at elsewhere
        """.trimIndent()
        assertEquals(
            "\"main\" prio=5 tid=1 Native\n  | group=\"main\" sCount=1\n  at com.ma7moud.reality3d.Slow.work(Slow.kt:10)",
            Anr.mainThread(traces),
        )
        val report = Diagnostics(context) { listOf(exit(ExitKind.FROZE, trace = traces.toByteArray())) }.report!!
        assertTrue(report.summary.startsWith("It stopped responding and Android closed it."))
        assertTrue(report.details, report.details.contains("at com.ma7moud.reality3d.Slow.work(Slow.kt:10)"))
    }

    /** A tombstone as Android writes it: the crashing thread [crashedFrames] (file, function, offset), another thread and a log. */
    private fun tombstone(crashedFrames: List<Triple<String, String, Long>>): ByteArray {
        fun thread(id: Long, name: String, frames: List<Triple<String, String, Long>>) = Proto().apply {
            varint(1, id)
            message(2, Proto().apply {
                varint(1, id)
                string(2, name)
                frames.forEachIndexed { i, (file, function, offset) ->
                    message(4, Proto().apply {
                        varint(1, 0x1000L + i)
                        if (function.isNotEmpty()) string(4, function)
                        varint(5, offset)
                        string(6, file)
                    })
                }
            })
        }
        return Proto().apply {
            varint(6, 4321)
            message(10, Proto().apply {
                varint(1, 11)
                string(2, "SIGSEGV")
                string(4, "SEGV_MAPERR")
                varint(9, 0x10)
            })
            message(16, thread(1, "main", listOf(Triple("/data/app/x/lib/arm64/libother.so", "Other", 0L))))
            message(16, thread(4321, "segmenter", crashedFrames))
            message(18, Proto().apply {
                string(1, "main")
                message(2, Proto().apply { varint(4, 3); string(5, "Reality3D"); string(6, "ignored") })
                message(2, Proto().apply { varint(4, 6); string(5, "Reality3D"); string(6, "Subject segmentation failed") })
            })
        }.bytes()
    }

    private class Proto {
        private val out = ByteArrayOutputStream()

        private fun raw(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun varint(field: Int, value: Long) {
            raw((field shl 3).toLong())
            raw(value)
        }

        fun varint(field: Int, value: Int) = varint(field, value.toLong())

        fun string(field: Int, value: String) = bytes(field, value.toByteArray())

        fun message(field: Int, value: Proto) = bytes(field, value.bytes())

        private fun bytes(field: Int, value: ByteArray) {
            raw(((field shl 3) or 2).toLong())
            raw(value.size.toLong())
            out.write(value)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
