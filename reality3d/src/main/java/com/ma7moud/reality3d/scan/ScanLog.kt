package com.ma7moud.reality3d.scan

import com.ma7moud.reality3d.quality.QualityReport
import java.util.Locale

/**
 * What happened during a scan, kept to be read afterwards: how long each step took, which warnings the person
 * saw and for how long, how many photos and depth maps came in, and how the build went. It is written out as
 * text to copy, so a scan that was hard to do can be understood from what the phone saw rather than from memory.
 */
class ScanLog(private val now: () -> Long = System::currentTimeMillis) {

    private class Episodes(var count: Int = 0, var millis: Long = 0)

    private val startedAt = now()
    private var lastAt = startedAt
    private var lastPhase: ScanPhase? = null
    private var lastWarning: String? = null
    private var scanningSince: Long? = null

    private val timeline = ArrayList<String>()
    private val phaseMillis = LinkedHashMap<ScanPhase, Long>()
    private val warnings = LinkedHashMap<String, Episodes>()
    private val messages = LinkedHashMap<String, Int>()
    private val events = ArrayList<String>()
    private var lastStatus: ScanStatus? = null
    private var scanningBoxSize: Float? = null
    private var build: String? = null

    @Synchronized
    fun onStatus(status: ScanStatus) {
        val t = now()
        val elapsed = t - lastAt
        lastPhase?.let { phaseMillis[it] = (phaseMillis[it] ?: 0L) + elapsed }
        lastWarning?.let { warnings.getValue(it).millis += elapsed }
        lastAt = t

        if (status.phase != lastPhase) {
            timeline += "${clock(t - startedAt)} ${describe(status.phase)}"
            if (status.phase == ScanPhase.SCANNING) {
                if (scanningSince == null) scanningSince = t
                scanningBoxSize = status.boxSize
            }
            lastPhase = status.phase
        }

        // What the person is being told to fix: the coach's warning (which leads with a tracking problem) or the tracking problem.
        val warning = status.coach?.takeIf { it.kind == CoachTip.Kind.WARNING }?.text ?: status.trackingProblem
        if (warning != lastWarning) {
            if (warning != null) warnings.getOrPut(warning) { Episodes() }.count++
            lastWarning = warning
        }
        status.message?.let { messages[it] = (messages[it] ?: 0) + if (lastStatus?.message == it) 0 else 1 }
        lastStatus = status
    }

    /** Something the person did, such as starting over. */
    @Synchronized
    fun event(text: String) {
        events += "${clock(now() - startedAt)} $text"
    }

    @Synchronized
    fun onBuilt(seconds: Float, quality: QualityReport, triangles: Int) {
        build = "Built in ${format(seconds)} s: $triangles triangles, quality ${quality.score}/100 ${quality.grade}" +
            quality.issues.joinToString("") { "\n  - $it" }
    }

    @Synchronized
    fun onBuildFailed(error: String) {
        build = "Building failed: $error"
    }

    /** The log as text; [header] says which app and phone, [engineDetails] what the scanning engine measured. */
    @Synchronized
    fun report(header: String, engineDetails: String = ""): String = buildString {
        appendLine(header)
        if (engineDetails.isNotBlank()) appendLine(engineDetails.trim())
        appendLine()
        appendLine("Timeline")
        timeline.forEach { appendLine("  $it") }
        if (phaseMillis.isNotEmpty()) {
            appendLine("Time in each step: " + phaseMillis.entries.joinToString(", ") { "${describe(it.key)} ${seconds(it.value)}" })
        }
        val status = lastStatus
        if (status != null) {
            val box = scanningBoxSize?.let { "box ${(it * 100).toInt()} cm, " } ?: ""
            appendLine("Scan: $box${(status.coverageFraction * 100).toInt()}% covered, ${status.photos} photos, ${status.depthFrames} depth maps")
        }
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("Warnings shown (times, seconds)")
            warnings.entries.sortedByDescending { it.value.millis }.forEach { (text, episodes) ->
                appendLine("  x${episodes.count} · ${seconds(episodes.millis)} · $text")
            }
        }
        if (messages.isNotEmpty()) {
            appendLine()
            appendLine("Messages")
            messages.forEach { (text, count) -> appendLine("  x$count · $text") }
        }
        if (events.isNotEmpty()) {
            appendLine()
            appendLine("Events")
            events.forEach { appendLine("  $it") }
        }
        build?.let {
            appendLine()
            appendLine(it)
        }
    }.trim()

    private fun describe(phase: ScanPhase) = when (phase) {
        ScanPhase.STARTING -> "starting the camera"
        ScanPhase.FIND_SURFACE -> "finding the surface"
        ScanPhase.PLACE_BOX -> "waiting for a tap on the object"
        ScanPhase.READY -> "box placed, waiting to start"
        ScanPhase.SCANNING -> "scanning"
        ScanPhase.BUILDING -> "building"
        ScanPhase.FAILED -> "failed"
    }

    private fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private fun seconds(millis: Long) = "${format(millis / 1000f)} s"

    private fun format(value: Float) = String.format(Locale.US, if (value < 10f) "%.1f" else "%.0f", value)
}
