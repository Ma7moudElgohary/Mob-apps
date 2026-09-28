package com.ma7moud.reality3d.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native work that can take the whole app down; it is written down while it runs. */
enum class Step(val label: String, val fallback: Fallback?) {
    FIND_OBJECTS("finding the objects in the photo (ML Kit)", Fallback.ONE_OBJECT),
    SEPARATE_OBJECT("separating the object from the background (ML Kit)", Fallback.NO_SEGMENTATION),
    DEPTH_GPU("running the depth model on the GPU", Fallback.NO_DEPTH_GPU),
    DEPTH_NPU("running the depth model on the NPU", Fallback.NO_DEPTH_NPU),
    DEPTH_CPU("running the depth model on the CPU", null),
}

/** A feature turned off because it crashed the app on this phone. */
enum class Fallback(val shortName: String, val label: String) {
    ONE_OBJECT("picking objects", "Separate objects are no longer told apart; the photo's main object is used as a whole."),
    NO_SEGMENTATION("automatic cut-out", "The object is no longer cut out automatically; paint it in with Edit outline."),
    NO_DEPTH_GPU("GPU depth", "The depth model no longer uses the GPU."),
    NO_DEPTH_NPU("NPU depth", "The depth model no longer uses the NPU."),
    ;

    internal val key get() = "off_$name"
}

/** Why the app closed unexpectedly last time: a line for the screen, the full text to send and what was turned off. */
class CrashReport(val summary: String, val details: String, val turnedOff: List<Fallback>)

enum class ExitKind { JAVA_CRASH, NATIVE_CRASH, FROZE, KILLED, OTHER }

/** One of Android's records of how the app's process ended. */
class ExitRecord(
    val timestamp: Long,
    val kind: ExitKind,
    /** The app was on screen. */
    val foreground: Boolean,
    val description: String?,
    /** The native crash's tombstone or the freeze's stack traces, read only when needed. */
    val trace: () -> ByteArray? = { null },
) {
    val isCrash: Boolean
        get() = when (kind) {
            ExitKind.JAVA_CRASH, ExitKind.NATIVE_CRASH, ExitKind.FROZE -> true
            ExitKind.KILLED -> foreground
            ExitKind.OTHER -> false
        }
}

/**
 * Keeps the app usable after a crash and says why it happened.
 *
 * Native work that no try/catch can guard (ML Kit, the GPU, the NPU) runs [during] a [Step] that is written to
 * disk first and removed after. If the app dies inside it, the next launch finds the step still there, turns
 * that feature off for good ([Fallback]) and reports it. Java crashes are written down as they happen; native
 * crashes and freezes come from Android's own exit records (Android 11 and later), native ones with the
 * crashing thread's stack from the tombstone.
 */
class Diagnostics(
    context: Context,
    /** Android's exit records, newest first; null where Android doesn't keep them. */
    private val exits: () -> List<ExitRecord>? = { exitRecords(context) },
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val crashFile = File(appContext.filesDir, CRASH_FILE)
    private val lock = Any()
    private var checked = false
    private var pending: CrashReport? = null

    /** Why the app closed unexpectedly last time, until [dismiss]ed. */
    val report: CrashReport?
        get() {
            check()
            return synchronized(lock) { pending }
        }

    fun isOff(fallback: Fallback): Boolean {
        check()
        return prefs.getBoolean(fallback.key, false)
    }

    /** Features turned off after crashes. */
    val turnedOff: List<Fallback> get() = Fallback.entries.filter { isOff(it) }

    /** Runs [block] as [step], so that if the app dies inside it the next launch knows where. */
    inline fun <T> during(step: Step, block: () -> T): T {
        begin(step)
        try {
            return block()
        } finally {
            end()
        }
    }

    fun begin(step: Step) {
        check()
        prefs.edit(commit = true) { putString(KEY_STEP, step.name) }
    }

    fun end() {
        prefs.edit(commit = true) { remove(KEY_STEP) }
    }

    fun dismiss() {
        synchronized(lock) { pending = null }
    }

    /** Turns every feature back on, for when a crash wasn't the feature's fault. */
    fun turnAllBackOn() {
        prefs.edit { Fallback.entries.forEach { remove(it.key) } }
    }

    /** Writes Java crashes down before the app closes; one handler per process, reporting to the latest instance. */
    fun watchJavaCrashes() {
        current = this
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                current?.recordJavaCrash(thread, error)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun recordJavaCrash(thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        crashFile.writeText("Thread: ${thread.name}\n" + stack.lineSequence().take(MAX_STACK_LINES).joinToString("\n"))
    }

    private fun check() {
        synchronized(lock) {
            if (checked) return
            checked = true
            pending = try {
                analyse()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read how the app last closed", e)
                null
            }
        }
    }

    private fun analyse(): CrashReport? {
        val now = System.currentTimeMillis()
        val since = prefs.getLong(KEY_SINCE, 0L).takeIf { it > 0L } ?: now
        val step = prefs.getString(KEY_STEP, null)?.let { name -> Step.entries.firstOrNull { it.name == name } }
        val javaCrash = if (crashFile.exists()) crashFile.readText() else null
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val records = try {
            exits()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read Android's exit records", e)
            null
        }
        val recent = records.orEmpty().filter { it.timestamp > lastSeen && now - it.timestamp < MAX_AGE_MS }
        val latest = recent.maxByOrNull { it.timestamp }
        val crash = recent.filter { it.isCrash }.maxByOrNull { it.timestamp }
        prefs.edit(commit = true) {
            putLong(KEY_SINCE, since)
            remove(KEY_STEP)
            if (latest != null) putLong(KEY_LAST_EXIT, latest.timestamp)
        }
        crashFile.delete()
        if (javaCrash == null && crash == null && (step == null || records != null)) return null

        // The step belongs to the run that just ended, so it only counts when that run crashed.
        val stepCrashed = step != null && (javaCrash != null || latest?.isCrash == true || records == null)
        val nativeTrace = if (javaCrash == null && crash?.kind == ExitKind.NATIVE_CRASH) crash.trace()?.let(Tombstone::read) else null
        val fallbacks = when {
            stepCrashed -> listOfNotNull(step.fallback)
            // A crash from before this version wrote steps down: guess the part from the crashing thread, or else
            // blame what runs as soon as a photo is chosen (ML Kit also throws from threads of its own).
            javaCrash == null && crash != null && crash.timestamp < since ->
                nativeTrace?.likelyCauses?.takeIf { it.isNotEmpty() } ?: listOf(Fallback.ONE_OBJECT)
            else -> emptyList()
        }
        if (fallbacks.isNotEmpty()) prefs.edit(commit = true) { fallbacks.forEach { putBoolean(it.key, true) } }

        val what = when {
            javaCrash != null -> "It crashed with an error in the app."
            crash?.kind == ExitKind.NATIVE_CRASH -> "It crashed in native code" + (nativeTrace?.signal?.let { " ($it)" } ?: "") + "."
            crash?.kind == ExitKind.FROZE -> "It stopped responding and Android closed it."
            crash?.kind == ExitKind.KILLED -> "Android closed it, most likely because the phone ran out of memory."
            else -> "It closed while it was busy."
        }
        val where = if (stepCrashed) " It was ${step.label}." else ""
        val summary = what + where + if (fallbacks.isEmpty()) {
            " Please send the report so it can be fixed."
        } else {
            fallbacks.joinToString("") { " ${it.label}" } + " Try again."
        }
        val details = buildString {
            appendLine(header(crash?.timestamp ?: now))
            appendLine(what + where)
            if (fallbacks.isNotEmpty()) appendLine("Turned off: ${fallbacks.joinToString { it.name }}")
            crash?.description?.takeIf { it.isNotBlank() }?.let { appendLine("Android: $it") }
            when {
                javaCrash != null -> append(javaCrash)
                nativeTrace != null -> append(nativeTrace.text)
                crash?.kind == ExitKind.FROZE -> crash.trace()?.let { append(Anr.mainThread(String(it))) }
            }
        }.trim().take(MAX_REPORT_CHARS)
        return CrashReport(summary, details, fallbacks)
    }

    private fun header(timestamp: Long): String {
        val version = try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        } catch (e: Exception) {
            "?"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
        return "Reality3D $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · $time"
    }

    companion object {
        private const val TAG = "Reality3DDiagnostics"
        private const val PREFS = "reality3d_diagnostics"
        private const val CRASH_FILE = "last_crash.txt"
        private const val KEY_STEP = "step"
        private const val KEY_SINCE = "since"
        private const val KEY_LAST_EXIT = "last_exit"
        private const val MAX_STACK_LINES = 120
        private const val MAX_REPORT_CHARS = 8000
        private const val MAX_RECORDS = 8
        private const val MAX_TRACE_BYTES = 1_048_576
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(3)

        @Volatile
        private var current: Diagnostics? = null
        private val installed = AtomicBoolean(false)

        private fun exitRecords(context: Context): List<ExitRecord>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) androidExitRecords(context) else null

        @RequiresApi(Build.VERSION_CODES.R)
        private fun androidExitRecords(context: Context): List<ExitRecord> {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS).map { info ->
                val kind = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH -> ExitKind.JAVA_CRASH
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.NATIVE_CRASH
                    ApplicationExitInfo.REASON_ANR -> ExitKind.FROZE
                    ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_SIGNALED,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitKind.KILLED
                    else -> ExitKind.OTHER
                }
                ExitRecord(
                    timestamp = info.timestamp,
                    kind = kind,
                    foreground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                    description = info.description,
                    trace = { info.traceInputStream?.use { it.readNBytesCompat(MAX_TRACE_BYTES) } },
                )
            }
        }

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
