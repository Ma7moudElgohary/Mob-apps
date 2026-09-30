package com.ma7moud.reality3d.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.content.edit
import java.io.Closeable
import java.util.Locale

/** Says the scan's guidance out loud with the phone's own voice. Silent when the phone has no voice for English. */
class CoachVoice(context: Context) : Closeable {

    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            // The listener can run before the constructor has returned, so look at the engine afterwards.
            handler.post {
                val voice = engine
                ready = status == TextToSpeech.SUCCESS && voice != null &&
                    voice.setLanguage(Locale.US).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
            }
        }
    }

    /** Says [text] now, cutting off what was being said. */
    fun say(text: String) {
        if (ready) engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    fun stop() {
        engine?.stop()
    }

    override fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private companion object {
        const val UTTERANCE = "scan-guidance"
    }
}

/** What the person chose about scanning, kept on the phone. */
internal class ScanPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("reality3d_scan", Context.MODE_PRIVATE)

    /** Guidance said out loud; on until turned off. */
    var voice: Boolean
        get() = prefs.getBoolean("voice", true)
        set(value) = prefs.edit { putBoolean("voice", value) }

    /** The how-to card has been read. */
    var tipsSeen: Boolean
        get() = prefs.getBoolean("tips_seen", false)
        set(value) = prefs.edit { putBoolean("tips_seen", value) }
}
