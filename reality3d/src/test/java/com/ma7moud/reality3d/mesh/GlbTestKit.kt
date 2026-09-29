package com.ma7moud.reality3d.mesh

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GLB files as the computer's photo builder writes them, for tests. */
internal object GlbTestKit {

    /** [glb] with the photo builder's note in its asset: whether the model is in real meters and how many photos it used. */
    fun withBuildNote(glb: ByteArray, scaleKnown: Boolean, photos: Int? = null, placed: Int? = null): ByteArray {
        val buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = buffer.getInt(12)
        val json = JSONObject(String(glb, 20, jsonLength, Charsets.UTF_8))
        val note = JSONObject().put("source", "photogrammetry").put("scaleKnown", scaleKnown)
        photos?.let { note.put("photos", it) }
        placed?.let { note.put("placed", it) }
        json.getJSONObject("asset").put("extras", JSONObject().put("reality3d", note))
        var text = json.toString().toByteArray(Charsets.UTF_8)
        text += ByteArray((4 - text.size % 4) % 4) { ' '.code.toByte() }
        val rest = glb.copyOfRange(20 + jsonLength, glb.size)
        val out = ByteBuffer.allocate(12 + 8 + text.size + rest.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity())
        out.putInt(text.size).putInt(0x4E4F534A).put(text).put(rest)
        return out.array()
    }
}
