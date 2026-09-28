package com.ma7moud.reality3d.ai3d

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class Ai3dResult(val engine: String, val glb: File, val message: String)

class Ai3dClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("reality3d_ai3d", Context.MODE_PRIVATE)
    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(value) { prefs.edit().putString("base_url", value.trimEnd('/')).apply() }

    suspend fun generate(bitmap: Bitmap, engine: String = "sf3d"): Ai3dResult = withContext(Dispatchers.IO) {
        require(baseUrl.isNotBlank()) { "Configure the AI 3D GPU backend URL first." }
        val image = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out); out.toByteArray() }
        val payload = JSONObject().put("engine", engine).put("imageBase64", Base64.getEncoder().encodeToString(image))
        val connection = (URL("$baseUrl/v1/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 600_000
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        val responseCode = connection.responseCode
        val text = (if (responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().readText()
        if (responseCode !in 200..299) error("AI 3D backend error $responseCode: $text")
        val json = JSONObject(text)
        val downloadUrl = json.getString("downloadUrl")
        val glb = File(context.cacheDir, "ai3d_${System.currentTimeMillis()}.glb")
        (URL(downloadUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 300_000 }.inputStream.use { input -> glb.outputStream().use { input.copyTo(it) } }
        Ai3dResult(json.optString("engine", engine), glb, json.optString("message", "AI 3D complete"))
    }
}
