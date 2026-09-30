package com.ma7moud.reality3d.remote

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Where the user's Reality3D server is (see the reality3d-server folder), its access code and the engine to use. */
data class RemoteSettings(val url: String = "", val token: String = "", val engine: String = "sf3d")

/**
 * One thing the server can do. [kind] says what it takes: "image" (a cut-out photo, for the AI engines) or "photos"
 * (a set of photos, for the photo builder). [why] is what to do about it when it isn't [available].
 */
data class RemoteEngine(
    val id: String,
    val name: String,
    val note: String,
    val available: Boolean,
    val kind: String = "image",
    val why: String? = null,
)

data class ServerInfo(val name: String, val version: String, val authRequired: Boolean, val engines: List<RemoteEngine>)

/** A problem to show to the user as it is. */
class RemoteException(message: String) : Exception(message)

/** An image-to-3D AI on the user's own computer, reached over the network. */
interface RemoteServer {
    suspend fun health(settings: RemoteSettings): ServerInfo

    /** Uploads the cut-out (PNG, transparent background) and waits for the model: the GLB file's bytes. */
    suspend fun generate(settings: RemoteSettings, png: ByteArray, onProgress: (fraction: Float?, message: String?) -> Unit): ByteArray

    /**
     * Uploads a zip of photos to the photo builder and waits for the model: the GLB file's bytes. Cancelling the
     * coroutine stops the work on the computer too.
     */
    suspend fun buildFromPhotos(
        settings: RemoteSettings,
        photos: File,
        options: PhotoBuildOptions,
        onProgress: (fraction: Float?, message: String?) -> Unit,
    ): ByteArray = throw RemoteException("This server can't build models from photos.")
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

/** The Reality3D server's HTTP API: GET /v1/health, POST /v1/jobs and /v1/photo-jobs, GET/DELETE /v1/jobs/{id}, GET /v1/jobs/{id}/result. */
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
                RemoteEngine(
                    engine.getString("id"),
                    engine.optString("name", engine.getString("id")),
                    engine.optString("note"),
                    engine.optBoolean("available"),
                    kind = engine.optString("kind", "image"),
                    why = engine.optString("why").takeIf { it.isNotBlank() },
                )
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
        return await(base, job.getString("id"), settings, onProgress, "The AI is working")
    }

    override suspend fun buildFromPhotos(
        settings: RemoteSettings,
        photos: File,
        options: PhotoBuildOptions,
        onProgress: (Float?, String?) -> Unit,
    ): ByteArray {
        val base = ServerAddress.normalize(settings.url)
        val megabytes = (photos.length() / (1024 * 1024)).coerceAtLeast(1)
        onProgress(null, "Sending the photos ($megabytes MB)")
        val path = "/v1/photo-jobs?quality=${options.quality.id}&mode=${options.mode.id}"
        val job = JSONObject(
            String(
                upload(base, path, settings, photos, "application/zip") { sent ->
                    onProgress(null, "Sending the photos (${sent * 100 / photos.length().coerceAtLeast(1)}%)")
                },
                Charsets.UTF_8,
            ),
        )
        return await(base, job.getString("id"), settings, onProgress, "Building the model")
    }

    /**
     * Waits for a job to finish and returns its model. When the coroutine is cancelled the computer is told to stop
     * too, so a job the person gave up on doesn't keep its processor busy.
     */
    private suspend fun await(base: String, id: String, settings: RemoteSettings, onProgress: (Float?, String?) -> Unit, working: String): ByteArray {
        try {
            while (true) {
                delay(pollMillis)
                val status = JSONObject(String(request(base, "/v1/jobs/$id", settings, readTimeout = 15_000), Charsets.UTF_8))
                val message = status.optString("message").takeIf { it.isNotBlank() && it != "null" }
                when (status.optString("status")) {
                    "done" -> {
                        onProgress(1f, "Downloading the model")
                        return request(base, "/v1/jobs/$id/result", settings, readTimeout = 180_000)
                    }
                    "failed" -> throw RemoteException(message ?: "The server couldn't make the model.")
                    "cancelled" -> throw RemoteException("The job was cancelled on the computer.")
                    "queued" -> onProgress(null, "Waiting for the server")
                    else -> onProgress(if (status.isNull("progress")) null else status.getDouble("progress").toFloat(), message ?: working)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { request(base, "/v1/jobs/$id", settings, method = "DELETE", readTimeout = 5_000) } }
            throw e
        }
    }

    /** Sends [file] as the body of a POST without holding it in memory; [onSent] gets the bytes sent so far. */
    private suspend fun upload(base: String, path: String, settings: RemoteSettings, file: File, contentType: String, onSent: (Long) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = try {
                URL(base + path).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw RemoteException("That doesn't look like an address.")
            }
            try {
                connection.connectTimeout = 6_000
                connection.readTimeout = 120_000
                if (settings.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${settings.token.trim()}")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(file.length())
                connection.outputStream.use { out ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        var lastReported = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            sent += count
                            if (sent - lastReported >= 512 * 1024 || sent == file.length()) {
                                lastReported = sent
                                onSent(sent)
                            }
                        }
                    }
                }
                readResponse(connection, base, notFound = OLD_SERVER)
            } catch (e: RemoteException) {
                throw e
            } catch (e: CancellationException) {
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

    private suspend fun request(
        base: String,
        path: String,
        settings: RemoteSettings,
        body: ByteArray? = null,
        contentType: String? = null,
        readTimeout: Int = 60_000,
        method: String? = null,
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
            if (method != null) connection.requestMethod = method
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            readResponse(connection, base)
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

    /** The body of a good answer; a bad one becomes the message to show. */
    private fun readResponse(connection: HttpURLConnection, base: String, notFound: String? = null): ByteArray {
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = connection.errorStream?.use { it.readBytes() }?.let { bytes ->
                runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("detail") }.getOrNull()
            }?.takeIf { it.isNotBlank() }
            throw RemoteException(
                when (code) {
                    401 -> "The server wants an access code, or this one is wrong."
                    // A server that knows the address says why; the framework's plain "Not Found" says the address is unknown.
                    404 -> detail?.takeIf { it != "Not Found" } ?: notFound ?: "That address answered, but it isn't a Reality3D server."
                    else -> detail ?: "The server answered with error $code."
                },
            )
        }
        return connection.inputStream.use { it.readBytes() }
    }

    private fun unreachable(base: String) =
        RemoteException("Can't reach $base. Is the server running, and is the phone on the same Wi-Fi as the computer?")

    private companion object {
        const val OLD_SERVER = "This server is older and has no photo builder. Update the reality3d-server folder on the computer."
    }
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
