package com.ma7moud.reality3d.remote

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID

/** Where the user's Reality3D server is (see the reality3d-server folder), its access code and the engine to use. */
data class RemoteSettings(val url: String = "", val token: String = "", val engine: String = "sf3d")

data class RemoteEngine(val id: String, val name: String, val note: String, val available: Boolean)

data class ServerInfo(val name: String, val version: String, val authRequired: Boolean, val engines: List<RemoteEngine>)

/** A problem to show to the user as it is. */
class RemoteException(message: String) : Exception(message)

/** An image-to-3D AI on the user's own computer, reached over the network. */
interface RemoteServer {
    suspend fun health(settings: RemoteSettings): ServerInfo

    /** Uploads the cut-out (PNG, transparent background) and waits for the model: the GLB file's bytes. */
    suspend fun generate(settings: RemoteSettings, png: ByteArray, onProgress: (fraction: Float?, message: String?) -> Unit): ByteArray
}

/** Server addresses: plain http only inside the home or office network, https everywhere else. */
object ServerAddress {

    /** "192.168.1.20:8765" → "http://192.168.1.20:8765"; throws [RemoteException] when it can't be used. */
    fun normalize(input: String): String {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) throw RemoteException("Type the address the server shows when it starts, like 192.168.1.20:8765.")
        val withScheme = if ("://" in text) text else "http://$text"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw RemoteException("That doesn't look like an address.")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: throw RemoteException("That doesn't look like an address.")
        if (scheme != "http" && scheme != "https") throw RemoteException("The address should start with http:// or https://.")
        if (scheme == "http" && !isLocal(host)) {
            throw RemoteException("Plain http only works for servers on your own network. Use https:// for anything else.")
        }
        return withScheme
    }

    /** Hosts on the local network: private and link-local addresses, shared (VPN) addresses and local names. */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || !h.contains('.') && !h.contains(':')) return true
        if (listOf(".local", ".lan", ".home", ".home.arpa", ".internal").any { h.endsWith(it) }) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (h.contains(':')) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        return false
    }
}

/** The Reality3D server's HTTP API: GET /v1/health, POST /v1/jobs, GET /v1/jobs/{id}[/result]. */
class HttpRemoteServer(private val pollMillis: Long = 1000) : RemoteServer {

    override suspend fun health(settings: RemoteSettings): ServerInfo {
        val base = ServerAddress.normalize(settings.url)
        val json = JSONObject(String(request(base, "/v1/health", settings, readTimeout = 10_000), Charsets.UTF_8))
        val engines = json.optJSONArray("engines")
        return ServerInfo(
            name = json.optString("name", "Reality3D server"),
            version = json.optString("version", ""),
            authRequired = json.optBoolean("authRequired", false),
            engines = List(engines?.length() ?: 0) { i ->
                val engine = engines!!.getJSONObject(i)
                RemoteEngine(engine.getString("id"), engine.optString("name", engine.getString("id")), engine.optString("note"), engine.optBoolean("available"))
            },
        )
    }

    override suspend fun generate(settings: RemoteSettings, png: ByteArray, onProgress: (Float?, String?) -> Unit): ByteArray {
        val base = ServerAddress.normalize(settings.url)
        onProgress(null, "Sending the photo")
        val boundary = "----Reality3D" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("engine", settings.engine)
            field("texture_size", "1024")
            field("faces", "30000")
            write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"cutout.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            write(png)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val job = JSONObject(String(request(base, "/v1/jobs", settings, body = body, contentType = "multipart/form-data; boundary=$boundary"), Charsets.UTF_8))
        val id = job.getString("id")
        while (true) {
            delay(pollMillis)
            val status = JSONObject(String(request(base, "/v1/jobs/$id", settings, readTimeout = 15_000), Charsets.UTF_8))
            val message = status.optString("message").takeIf { it.isNotBlank() && it != "null" }
            when (status.optString("status")) {
                "done" -> {
                    onProgress(1f, "Downloading the model")
                    return request(base, "/v1/jobs/$id/result", settings, readTimeout = 120_000)
                }
                "failed" -> throw RemoteException(message ?: "The server couldn't make the model.")
                "queued" -> onProgress(null, "Waiting for the server")
                else -> onProgress(if (status.isNull("progress")) null else status.getDouble("progress").toFloat(), message ?: "The AI is working")
            }
        }
    }

    private suspend fun request(
        base: String,
        path: String,
        settings: RemoteSettings,
        body: ByteArray? = null,
        contentType: String? = null,
        readTimeout: Int = 60_000,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw RemoteException("That doesn't look like an address.")
        }
        try {
            connection.connectTimeout = 6_000
            connection.readTimeout = readTimeout
            if (settings.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${settings.token.trim()}")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.use { it.readBytes() }?.let { bytes ->
                    runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("detail") }.getOrNull()
                }?.takeIf { it.isNotBlank() }
                throw RemoteException(
                    when (code) {
                        401 -> "The server wants an access code, or this one is wrong."
                        404 -> detail ?: "That address answered, but it isn't a Reality3D server."
                        else -> detail ?: "The server answered with error $code."
                    },
                )
            }
            connection.inputStream.use { it.readBytes() }
        } catch (e: RemoteException) {
            throw e
        } catch (e: ConnectException) {
            throw unreachable(base)
        } catch (e: NoRouteToHostException) {
            throw unreachable(base)
        } catch (e: UnknownHostException) {
            throw unreachable(base)
        } catch (e: SocketTimeoutException) {
            throw RemoteException("The server at $base didn't answer in time.")
        } catch (e: IOException) {
            throw RemoteException("Lost the connection to the server: ${e.message ?: e.javaClass.simpleName}.")
        } finally {
            connection.disconnect()
        }
    }

    private fun unreachable(base: String) =
        RemoteException("Can't reach $base. Is the server running, and is the phone on the same Wi-Fi as the computer?")
}

/** The server settings, kept on the phone. */
class RemoteSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("reality3d_remote", Context.MODE_PRIVATE)

    fun load() = RemoteSettings(prefs.getString("url", "") ?: "", prefs.getString("token", "") ?: "", prefs.getString("engine", "sf3d") ?: "sf3d")

    fun save(settings: RemoteSettings) {
        prefs.edit {
            putString("url", settings.url)
            putString("token", settings.token)
            putString("engine", settings.engine)
        }
    }
}
