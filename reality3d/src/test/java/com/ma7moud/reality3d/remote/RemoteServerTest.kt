package com.ma7moud.reality3d.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.mesh.GlbWriter
import com.ma7moud.reality3d.mesh.Mesh3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RemoteServerTest {

    private val glb = GlbWriter.write(
        Mesh3D(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), FloatArray(9), null, intArrayOf(0, 1, 2), solid = false, subjectIsolated = true),
        texture = null,
    )
    private var upload: ByteArray = ByteArray(0)
    private var authorization: String? = null
    private val polls = AtomicInteger()

    /** Behaves like reality3d-server: health, a job that runs for two polls, then the GLB. */
    private val server = TinyHttpServer { method, path, headers, body ->
        when {
            path == "/v1/health" -> json(200, """{"name":"Reality3D server","version":"1.0","authRequired":true,"engines":[{"id":"preview","name":"Quick preview (CPU)","note":"test","available":true},{"id":"sf3d","name":"Stable Fast 3D","note":"","available":false}]}""")
            !path.startsWith("/v1/jobs") -> json(404, """{"detail":"Not Found"}""")
            else -> {
                authorization = headers["authorization"]
                when {
                    authorization != "Bearer code" -> json(401, """{"detail":"Wrong or missing access code"}""")
                    method == "POST" -> {
                        upload = body
                        json(202, """{"id":"job1","status":"queued"}""")
                    }
                    path.endsWith("/result") -> Triple(200, "model/gltf-binary", glb)
                    polls.incrementAndGet() == 1 -> json(200, """{"id":"job1","status":"running","progress":0.5,"message":"Generating the shape"}""")
                    else -> json(200, """{"id":"job1","status":"done","progress":1.0,"message":null}""")
                }
            }
        }
    }
    private val url = "127.0.0.1:${server.port}"

    private fun json(code: Int, body: String) = Triple(code, "application/json", body.toByteArray())

    @After
    fun tearDown() {
        server.close()
        photoServer?.close()
    }

    private var photoServer: TinyHttpServer? = null
    private var photoBody: ByteArray = ByteArray(0)
    private var photoPath: String = ""
    private val cancelled = AtomicBoolean(false)
    private val statusPolls = AtomicInteger()

    /** Behaves like the photo builder: takes a zip at /v1/photo-jobs, works, then hands over the GLB, or is told to stop. */
    private fun photoBuilder(finalStatus: String = "done"): String {
        val started = TinyHttpServer { method, path, _, body ->
            when {
                path == "/v1/health" -> json(200, """{"name":"Reality3D server","version":"1.1","authRequired":false,"engines":[{"id":"photogrammetry","name":"Photos → 3D","note":"","available":false,"kind":"photos","why":"Run  pip install pycolmap"}]}""")
                method == "POST" && path.startsWith("/v1/photo-jobs") -> {
                    photoPath = path
                    photoBody = body
                    json(202, """{"id":"job7","status":"queued"}""")
                }
                method == "DELETE" -> {
                    cancelled.set(true)
                    json(200, """{"id":"job7","status":"cancelled"}""")
                }
                path.endsWith("/result") -> Triple(200, "model/gltf-binary", glb)
                path == "/v1/jobs/job7" -> when (statusPolls.incrementAndGet()) {
                    1 -> json(200, """{"id":"job7","status":"queued","progress":null,"message":null}""")
                    2 -> json(200, """{"id":"job7","status":"running","progress":0.42,"message":"Building the surface from the photos"}""")
                    else -> if (finalStatus == "done") json(200, """{"id":"job7","status":"done","progress":1.0,"message":null}""")
                    else if (finalStatus == "running") json(200, """{"id":"job7","status":"running","progress":0.5,"message":"Still going"}""")
                    else json(200, """{"id":"job7","status":"$finalStatus","progress":null,"message":"The photos didn't show enough detail."}""")
                }
                else -> json(404, """{"detail":"Not Found"}""")
            }
        }
        photoServer = started
        return "127.0.0.1:${started.port}"
    }

    private fun zipFile(bytes: ByteArray): File = File.createTempFile("photos", ".zip").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun talksToTheServer() = runBlocking {
        val client = HttpRemoteServer(pollMillis = 10)
        val info = client.health(RemoteSettings(url))
        assertEquals(listOf("preview", "sf3d"), info.engines.map { it.id })
        assertEquals(listOf(true, false), info.engines.map { it.available })
        assertTrue(info.authRequired)

        val progress = ArrayList<Pair<Float?, String?>>()
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 42)
        val result = client.generate(RemoteSettings(url, token = "code", engine = "preview"), png) { f, m -> progress += f to m }
        assertArrayEquals(glb, result)
        assertEquals("Bearer code", authorization)
        val body = String(upload, Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"engine\"\r\n\r\npreview"))
        assertTrue(body.contains("filename=\"cutout.png\""))
        assertTrue(body.contains(String(png, Charsets.ISO_8859_1)))
        assertTrue(progress.contains(0.5f to "Generating the shape"))
        assertEquals(1f to "Downloading the model", progress.last())
    }

    @Test
    fun sendsAZipOfPhotosAndBringsBackTheModel() = runBlocking {
        val address = photoBuilder()
        val client = HttpRemoteServer(pollMillis = 10)
        val engine = client.health(RemoteSettings(address)).engines.single()
        assertEquals("photos", engine.kind)
        assertEquals("Run  pip install pycolmap", engine.why)
        assertTrue(!engine.available)

        val zip = ByteArray(300_000) { (it * 31).toByte() }
        val steps = ArrayList<Pair<Float?, String?>>()
        val result = client.buildFromPhotos(
            RemoteSettings(address), zipFile(zip), PhotoBuildOptions(BuildQuality.HIGH, BuildMode.SCENE),
        ) { f, m -> steps += f to m }
        assertArrayEquals(glb, result)
        assertArrayEquals(zip, photoBody)
        assertEquals("/v1/photo-jobs?quality=high&mode=scene", photoPath)
        assertEquals(null to "Sending the photos (100%)", steps.first { it.second?.startsWith("Sending the photos (") == true && it.second!!.endsWith("%)") })
        assertTrue(steps.contains(null to "Waiting for the server"))
        assertTrue(steps.contains(0.42f to "Building the surface from the photos"))
        assertEquals(1f to "Downloading the model", steps.last())
        assertTrue("nothing to cancel when it went well", !cancelled.get())
    }

    @Test
    fun givingUpTellsTheComputerToStopWorking() = runBlocking {
        val address = photoBuilder(finalStatus = "running")
        val client = HttpRemoteServer(pollMillis = 10)
        val job = launch(Dispatchers.IO) {
            client.buildFromPhotos(RemoteSettings(address), zipFile(ByteArray(1000)), PhotoBuildOptions()) { _, _ -> }
        }
        withTimeout(10_000) { while (statusPolls.get() < 3) kotlinx.coroutines.delay(10) }
        job.cancelAndJoin()
        assertTrue("the computer was told to stop", cancelled.get())
    }

    @Test
    fun explainsWhyABuildFailedOrWasStopped() {
        val client = HttpRemoteServer(pollMillis = 10)
        val failed = assertThrows(RemoteException::class.java) {
            runBlocking { client.buildFromPhotos(RemoteSettings(photoBuilder("failed")), zipFile(ByteArray(10)), PhotoBuildOptions()) { _, _ -> } }
        }
        assertEquals("The photos didn't show enough detail.", failed.message)
        statusPolls.set(0)
        val stopped = assertThrows(RemoteException::class.java) {
            runBlocking { client.buildFromPhotos(RemoteSettings(photoBuilder("cancelled")), zipFile(ByteArray(10)), PhotoBuildOptions()) { _, _ -> } }
        }
        assertEquals("The job was cancelled on the computer.", stopped.message)
        // An older server has no photo builder at all.
        val old = assertThrows(RemoteException::class.java) {
            runBlocking { client.buildFromPhotos(RemoteSettings(url), zipFile(ByteArray(10)), PhotoBuildOptions()) { _, _ -> } }
        }
        assertEquals("This server is older and has no photo builder. Update the reality3d-server folder on the computer.", old.message)
    }

    @Test
    fun explainsWhatWentWrong() {
        val client = HttpRemoteServer(pollMillis = 10)
        val wrongCode = assertThrows(RemoteException::class.java) {
            runBlocking { client.generate(RemoteSettings(url, token = "nope", engine = "preview"), ByteArray(4)) { _, _ -> } }
        }
        assertEquals("The server wants an access code, or this one is wrong.", wrongCode.message)
        // Nothing listens on port 1. (A server just closed can still take one last connection.)
        val down = assertThrows(RemoteException::class.java) { runBlocking { client.health(RemoteSettings("127.0.0.1:1")) } }
        assertTrue(down.message, down.message!!.startsWith("Can't reach http://127.0.0.1:1."))
    }

    @Test
    fun plainHttpOnlyInsideTheLocalNetwork() {
        assertEquals("http://192.168.1.20:8765", ServerAddress.normalize(" 192.168.1.20:8765/ "))
        assertEquals("http://desktop.local:8765", ServerAddress.normalize("desktop.local:8765"))
        assertEquals("http://100.101.102.103:8765", ServerAddress.normalize("100.101.102.103:8765"))
        assertEquals("http://10.0.0.5", ServerAddress.normalize("http://10.0.0.5"))
        assertEquals("https://gpu.example.com", ServerAddress.normalize("https://gpu.example.com/"))
        assertThrows(RemoteException::class.java) { ServerAddress.normalize("http://gpu.example.com") }
        assertThrows(RemoteException::class.java) { ServerAddress.normalize("8.8.8.8:8765") }
        assertThrows(RemoteException::class.java) { ServerAddress.normalize("ftp://192.168.1.2") }
        assertThrows(RemoteException::class.java) { ServerAddress.normalize("") }
    }
}

/** Just enough HTTP/1.1 for the client under test: one request per connection. */
private class TinyHttpServer(
    private val handle: (method: String, path: String, headers: Map<String, String>, body: ByteArray) -> Triple<Int, String, ByteArray>,
) {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                runCatching { client.use { serve(it) } }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val head = ByteArrayOutputStream()
        while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val (method, path) = lines[0].split(" ").let { it[0] to it[1] }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        val (code, type, payload) = handle(method, path, headers, body)
        val out = client.getOutputStream()
        out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        out.flush()
    }

    fun close() = socket.close()
}
