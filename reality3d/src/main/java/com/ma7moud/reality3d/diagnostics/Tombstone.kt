package com.ma7moud.reality3d.diagnostics

/**
 * What a native crash's tombstone says: the signal, the crashing thread's stack and the app's last warnings, and
 * the features that most likely caused it.
 */
class NativeCrash(val signal: String?, val text: String, val likelyCauses: List<Fallback>)

/**
 * Reads the tombstone Android keeps for a native crash (a protobuf, see tombstone.proto in AOSP's debuggerd),
 * keeping only what helps: the signal, the abort message, the crashing thread's backtrace and the last
 * warnings and errors in the app's log.
 */
internal object Tombstone {

    private const val MAX_FRAMES = 24
    private const val MAX_LOG_LINES = 16

    fun read(bytes: ByteArray): NativeCrash? = try {
        parse(bytes)
    } catch (e: Exception) {
        null
    }

    private class Frame(val file: String, val function: String, val offset: Long, val relPc: Long)

    private class ThreadInfo(val id: Long, val name: String, val frames: List<Frame>)

    private fun parse(bytes: ByteArray): NativeCrash {
        val r = ProtoReader(bytes, 0, bytes.size)
        var tid = -1L
        var signal: String? = null
        var abort: String? = null
        val threads = ArrayList<ThreadInfo>()
        val logs = ArrayList<String>()
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 6 && wire == 0 -> tid = r.varint()
                field == 10 && wire == 2 -> signal = signal(r.message())
                field == 14 && wire == 2 -> abort = r.string()
                field == 16 && wire == 2 -> thread(r.message())?.let(threads::add)
                field == 18 && wire == 2 -> logs += logBuffer(r.message())
                else -> r.skip(wire)
            }
        }
        val crashed = threads.firstOrNull { it.id == tid } ?: threads.firstOrNull()
        val text = buildString {
            signal?.let { appendLine("Signal: $it") }
            abort?.takeIf { it.isNotBlank() }?.let { appendLine("Abort message: $it") }
            if (crashed != null) {
                appendLine("Crashed thread: ${crashed.name} (${crashed.id})")
                crashed.frames.take(MAX_FRAMES).forEachIndexed { i, frame ->
                    append("  #%02d pc %08x  %s".format(i, frame.relPc, frame.file))
                    if (frame.function.isNotEmpty()) append(" (${frame.function}+${frame.offset})")
                    appendLine()
                }
            }
            if (logs.isNotEmpty()) {
                appendLine("Last warnings in the app's log:")
                logs.takeLast(MAX_LOG_LINES).forEach { appendLine("  $it") }
            }
        }
        val clues = (crashed?.frames.orEmpty().flatMap { listOf(it.file, it.function) } + listOfNotNull(abort)).joinToString("\n").lowercase()
        return NativeCrash(signal, text.trimEnd(), guess(clues))
    }

    /** Which features to turn off, from the libraries on the crashing thread. */
    private fun guess(clues: String): List<Fallback> {
        val gpu = listOf("gpu", "opencl", "vulkan", "gles", "mali", "adreno", "xclipse").any { it in clues }
        return when {
            // ML Kit's segmentation runs from Google Play services' own libraries inside the app.
            "com.google.android.gms" in clues || "mlkit" in clues -> listOf(Fallback.ONE_OBJECT)
            "litert" in clues || "tflite" in clues -> when {
                gpu -> listOf(Fallback.NO_DEPTH_GPU)
                listOf("npu", "qnn", "neuron", "dispatch", "eden", "enn").any { it in clues } -> listOf(Fallback.NO_DEPTH_NPU)
                else -> emptyList()
            }
            // Only the graphics driver on the stack: both ML Kit and the depth model use the GPU.
            gpu -> listOf(Fallback.ONE_OBJECT, Fallback.NO_DEPTH_GPU)
            else -> emptyList()
        }
    }

    private fun signal(r: ProtoReader): String {
        var name = ""
        var code = ""
        var fault: Long? = null
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 2 && wire == 2 -> name = r.string()
                field == 4 && wire == 2 -> code = r.string()
                field == 9 && wire == 0 -> fault = r.varint()
                else -> r.skip(wire)
            }
        }
        return buildString {
            append(name.ifEmpty { "signal" })
            if (code.isNotEmpty()) append(" ($code)")
            fault?.let { append(", fault address 0x%x".format(it)) }
        }
    }

    /** One entry of the tombstone's map from thread id to thread. */
    private fun thread(entry: ProtoReader): ThreadInfo? {
        var info: ThreadInfo? = null
        while (entry.hasMore()) {
            val (field, wire) = entry.tag()
            if (field == 2 && wire == 2) info = threadBody(entry.message()) else entry.skip(wire)
        }
        return info
    }

    private fun threadBody(r: ProtoReader): ThreadInfo {
        var id = -1L
        var name = ""
        val frames = ArrayList<Frame>()
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> id = r.varint()
                field == 2 && wire == 2 -> name = r.string()
                field == 4 && wire == 2 -> frames += frame(r.message())
                else -> r.skip(wire)
            }
        }
        return ThreadInfo(id, name, frames)
    }

    private fun frame(r: ProtoReader): Frame {
        var relPc = 0L
        var function = ""
        var offset = 0L
        var file = ""
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> relPc = r.varint()
                field == 4 && wire == 2 -> function = r.string()
                field == 5 && wire == 0 -> offset = r.varint()
                field == 6 && wire == 2 -> file = r.string()
                else -> r.skip(wire)
            }
        }
        return Frame(file, function, offset, relPc)
    }

    private fun logBuffer(r: ProtoReader): List<String> {
        val lines = ArrayList<String>()
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            if (field == 2 && wire == 2) logMessage(r.message())?.let(lines::add) else r.skip(wire)
        }
        return lines
    }

    /** A log line at warning level or above, as "W Tag: message". */
    private fun logMessage(r: ProtoReader): String? {
        var priority = 0L
        var tag = ""
        var message = ""
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 4 && wire == 0 -> priority = r.varint()
                field == 5 && wire == 2 -> tag = r.string()
                field == 6 && wire == 2 -> message = r.string()
                else -> r.skip(wire)
            }
        }
        if (priority < WARN) return null
        val letter = when (priority) {
            WARN -> 'W'
            ERROR -> 'E'
            else -> 'F'
        }
        return "$letter $tag: ${message.trim().take(300)}"
    }

    private const val WARN = 5L
    private const val ERROR = 6L
}

/** The main thread's stack from the traces Android takes when an app stops responding. */
internal object Anr {
    private const val MAX_LINES = 40

    fun mainThread(traces: String): String {
        val lines = traces.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return lines.take(MAX_LINES).joinToString("\n")
        val block = lines.drop(start).takeWhile { it.isNotBlank() }
        return block.take(MAX_LINES).joinToString("\n")
    }
}

/** Just enough of the protobuf wire format to walk a message and read its fields. */
internal class ProtoReader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {

    fun hasMore() = pos < end

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < end) { "truncated varint" }
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    /** The next field's number and wire type. */
    fun tag(): Pair<Int, Int> {
        val tag = varint()
        return (tag ushr 3).toInt() to (tag and 7).toInt()
    }

    /** A length-delimited field read as a message of its own. */
    fun message(): ProtoReader {
        val length = length()
        return ProtoReader(bytes, pos, pos + length).also { pos += length }
    }

    fun string(): String {
        val length = length()
        return String(bytes, pos, length, Charsets.UTF_8).also { pos += length }
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> {
                // Read the length first: it moves past its own bytes.
                val length = length()
                pos += length
            }
            5 -> pos += 4
            else -> throw IllegalArgumentException("unsupported wire type $wire")
        }
        require(pos <= end) { "truncated field" }
    }

    private fun length(): Int {
        val length = varint()
        require(length >= 0 && length <= end - pos) { "bad length" }
        return length.toInt()
    }
}
