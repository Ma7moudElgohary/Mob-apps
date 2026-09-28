package com.ma7moud.reality3d.ar

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.ma7moud.reality3d.scan.CameraPose
import com.ma7moud.reality3d.scan.CoverageTracker
import com.ma7moud.reality3d.scan.DepthFrame
import com.ma7moud.reality3d.scan.Intrinsics
import com.ma7moud.reality3d.scan.Keyframe
import com.ma7moud.reality3d.scan.KeyframeImage
import com.ma7moud.reality3d.scan.KeyframeSelector
import com.ma7moud.reality3d.scan.ScanBox
import com.ma7moud.reality3d.scan.ScanCapture
import com.ma7moud.reality3d.scan.ScanEngine
import com.ma7moud.reality3d.scan.ScanEngineFactory
import com.ma7moud.reality3d.scan.ScanPhase
import com.ma7moud.reality3d.scan.ScanReconstructor
import com.ma7moud.reality3d.scan.ScanStatus
import com.ma7moud.reality3d.scan.ScanSupport
import com.ma7moud.reality3d.scan.TsdfVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ArCoreScanFactory : ScanEngineFactory {

    override fun check(activity: Activity, userRequestedInstall: Boolean): ScanSupport {
        val availability = ArCoreApk.getInstance().checkAvailability(activity)
        if (availability.isTransient) return ScanSupport.Checking
        if (availability.isUnsupported) return ScanSupport.Unsupported(UNSUPPORTED)
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALLED -> ScanSupport.Ready
                else -> ScanSupport.Installing
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            ScanSupport.Unsupported("Scanning needs Google Play Services for AR. Install it from the Play Store to use this.")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            ScanSupport.Unsupported(UNSUPPORTED)
        } catch (e: Exception) {
            ScanSupport.Unsupported("ARCore isn't working right now (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    override fun create(context: Context): ScanEngine = ArCoreScanEngine(context.applicationContext)

    private companion object {
        const val UNSUPPORTED = "This phone doesn't support ARCore, which the 360° scan needs."
    }
}

/**
 * Scanning with ARCore: tracks the phone, fuses ARCore depth maps into a [TsdfVolume] over the scan box
 * about four times a second, and keeps photos from evenly spread directions for colouring and export.
 */
class ArCoreScanEngine(private val context: Context) : ScanEngine {

    private val _status = MutableStateFlow(ScanStatus())
    override val status: StateFlow<ScanStatus> = _status.asStateFlow()

    // UI thread.
    private var session: Session? = null
    private var view: ArScanView? = null
    private var closed = false

    // Shared with the GL thread.
    @Volatile private var resumed = false
    @Volatile private var phase = ScanPhase.STARTING
    @Volatile private var boxSize = ScanStatus.DEFAULT_BOX_SIZE
    @Volatile private var anchor: Anchor? = null
    @Volatile private var box: ScanBox? = null
    @Volatile private var volume: TsdfVolume? = null
    @Volatile private var message: String? = null
    @Volatile private var textureBound = false
    @Volatile private var geometryPending = false
    @Volatile private var lastPublishAt = 0L
    @Volatile private var trackingProblem: String? = null
    @Volatile private var phoneAzimuth: Float? = null
    @Volatile private var phoneRing: Int? = null
    private val taps = ConcurrentLinkedQueue<FloatArray>()

    // Guarded by scanLock: the GL thread fills them while the buttons reset them.
    private val scanLock = Any()
    private val coverage = CoverageTracker()
    private val selector = KeyframeSelector()
    private val keyframes = CopyOnWriteArrayList<Keyframe>()
    private val depthFrames = AtomicInteger()

    // GL thread only.
    private var cameraTexture = 0
    private var geometry = IntArray(3)
    private var lastDepthAt = 0L
    private var previousForward: FloatArray? = null
    private var previousPosition: FloatArray? = null
    private var previousFrameAt = 0L
    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val poseMatrix = FloatArray(16)
    private val overlayVertices = FloatArray(7 * 256)
    private val dot = FloatArray(3)
    private val projected = FloatArray(3)

    private val fusion = Executors.newSingleThreadExecutor()
    private val encoder = Executors.newSingleThreadExecutor()
    private val pool = Executors.newFixedThreadPool(FUSION_THREADS)
    private val fusing = AtomicBoolean(false)
    private val encoding = AtomicBoolean(false)

    /** Where the user tapped: the object's surface, the camera's spot, and the table under it. */
    private class Anchor(val x: Float, val y: Float, val z: Float, val cameraX: Float, val cameraZ: Float, val floorY: Float?)

    override fun createView(context: Context): View {
        val created = ArScanView(context, this)
        view = created
        textureBound = false
        return created
    }

    override fun setBoxSize(meters: Float) {
        boxSize = meters.coerceIn(ScanStatus.MIN_BOX_SIZE, ScanStatus.MAX_BOX_SIZE)
        placeBox()
        publish(force = true)
    }

    override fun startScanning() {
        val current = box ?: return
        synchronized(scanLock) {
            coverage.reset()
            selector.reset()
            keyframes.clear()
            depthFrames.set(0)
            volume = TsdfVolume(current)
            message = null
            phase = ScanPhase.SCANNING
        }
        publish(force = true)
    }

    override fun restart() {
        synchronized(scanLock) {
            volume = null
            coverage.reset()
            selector.reset()
            keyframes.clear()
            depthFrames.set(0)
            phase = if (box != null) ScanPhase.READY else ScanPhase.PLACE_BOX
        }
        publish(force = true)
    }

    override fun resume() {
        if (closed) return
        if (session == null) {
            session = try {
                openSession()
            } catch (e: UnavailableException) {
                fail("ARCore couldn't start: ${e.message ?: e.javaClass.simpleName}.")
                return
            } catch (e: Exception) {
                fail("ARCore couldn't start: ${e.message ?: e.javaClass.simpleName}.")
                return
            }
            geometryPending = true
            textureBound = false
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            fail("The camera is being used by another app. Close it and try again.")
            return
        }
        view?.onResume()
        resumed = true
        if (phase == ScanPhase.STARTING) phase = ScanPhase.FIND_SURFACE
        publish(force = true)
    }

    override fun pause() {
        resumed = false
        // The GL thread stops before the session pauses, so update() never runs on a paused session.
        view?.onPause()
        session?.pause()
    }

    override suspend fun build(progress: (String) -> Unit): ScanCapture = withContext(Dispatchers.Default) {
        val scanned = volume ?: error("nothing has been scanned yet")
        phase = ScanPhase.BUILDING
        publish(force = true)
        progress("Finishing the depth maps…")
        fusion.submit {}.get()
        encoder.submit {}.get()
        val photos = keyframes.toList()
        val mesh = ScanReconstructor.reconstruct(scanned, photos.size, { decode(photos[it]) }, photos.firstOrNull()?.pose, progress)
        ScanCapture(mesh, photos)
    }

    override fun close() {
        if (closed) return
        closed = true
        resumed = false
        view?.onPause()
        view = null
        session?.close()
        session = null
        fusion.shutdown()
        encoder.shutdown()
        pool.shutdown()
    }

    internal fun onTap(x: Float, y: Float) {
        if (phase == ScanPhase.FIND_SURFACE || phase == ScanPhase.PLACE_BOX || phase == ScanPhase.READY) taps.offer(floatArrayOf(x, y))
    }

    // ---- GL thread ----

    internal fun onSurfaceCreated(textureId: Int) {
        cameraTexture = textureId
        textureBound = false
    }

    internal fun onSurfaceChanged(rotation: Int, width: Int, height: Int) {
        geometry = intArrayOf(rotation, width, height)
        geometryPending = true
    }

    internal fun onDrawFrame(background: CameraBackground, overlay: OverlayRenderer) {
        val current = session ?: return
        if (!resumed) return
        if (!textureBound && cameraTexture != 0) {
            current.setCameraTextureName(cameraTexture)
            textureBound = true
        }
        if (geometryPending) {
            current.setDisplayGeometry(geometry[0], geometry[1], geometry[2])
            geometryPending = false
        }
        val frame = try {
            current.update()
        } catch (e: Exception) {
            Log.w(TAG, "ARCore update failed", e)
            return
        }
        background.draw(frame)
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            trackingProblem = trackingMessage(camera.trackingFailureReason)
            previousForward = null
            publish()
            return
        }
        trackingProblem = null
        taps.poll()?.let { placeFromTap(frame, camera, current, it[0], it[1]) }
        if (phase == ScanPhase.FIND_SURFACE && current.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }) {
            phase = ScanPhase.PLACE_BOX
        }
        camera.pose.toMatrix(poseMatrix, 0)
        val now = SystemClock.uptimeMillis()
        if (phase == ScanPhase.SCANNING) scanFrame(frame, camera, now)
        rememberMotion(now)
        camera.getProjectionMatrix(projection, 0, 0.03f, 30f)
        camera.getViewMatrix(viewMatrix, 0)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)
        drawOverlay(overlay)
        publish()
    }

    private fun placeFromTap(frame: Frame, camera: Camera, session: Session, x: Float, y: Float) {
        val hit = frame.hitTest(x, y).firstOrNull { result ->
            when (val trackable = result.trackable) {
                is Plane -> trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(result.hitPose)
                is DepthPoint, is Point -> trackable.trackingState == TrackingState.TRACKING
                else -> false
            }
        }
        if (hit == null) {
            message = "Couldn't find anything there. Move a little and tap the object again."
            return
        }
        val pose = hit.hitPose
        val floor = floorBelow(session, pose.tx(), pose.ty(), pose.tz())
        val cameraPose = camera.pose
        anchor = Anchor(pose.tx(), pose.ty(), pose.tz(), cameraPose.tx(), cameraPose.tz(), floor)
        message = if (floor == null) "No table found under the object, so the box floats. Scanning still works." else null
        placeBox()
        phase = ScanPhase.READY
    }

    /** The highest upward-facing surface under the point, if ARCore has found one. */
    private fun floorBelow(session: Session, x: Float, y: Float, z: Float): Float? {
        var best: Float? = null
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING || plane.subsumedBy != null) continue
            val planeY = plane.centerPose.ty()
            if (planeY > y + 0.02f || planeY < y - 1.5f) continue
            if (!plane.isPoseInPolygon(Pose.makeTranslation(x, planeY, z))) continue
            if (best == null || planeY > best) best = planeY
        }
        return best
    }

    private fun placeBox() {
        val tapped = anchor ?: return
        box = ScanBox.around(tapped.x, tapped.y, tapped.z, tapped.cameraX, tapped.cameraZ, boxSize, tapped.floorY)
    }

    private fun scanFrame(frame: Frame, camera: Camera, now: Long) {
        val scanBox = box ?: return
        val scanVolume = volume ?: return
        val pose = CameraPose(poseMatrix.copyOf())
        val toCamera = pose.directionFrom(scanBox.centerX, scanBox.centerY, scanBox.centerZ)
        phoneAzimuth = CoverageTracker.azimuthDegrees(toCamera[0], toCamera[2])
        val cell = CoverageTracker.cellOf(toCamera[0], toCamera[1], toCamera[2])
        phoneRing = if (cell == CoverageTracker.TOP_CELL) CoverageTracker.TOP_RING else cell / CoverageTracker.SEGMENTS

        if (now - lastDepthAt >= DEPTH_INTERVAL_MS && fusing.compareAndSet(false, true)) {
            val depth = acquireDepth(frame, camera, pose)
            if (depth == null) {
                fusing.set(false)
            } else {
                lastDepthAt = now
                fusion.execute {
                    try {
                        scanVolume.integrate(depth, pool, FUSION_THREADS)
                        depthFrames.incrementAndGet()
                    } catch (e: Exception) {
                        Log.w(TAG, "Depth fusion failed", e)
                    } finally {
                        fusing.set(false)
                    }
                }
            }
        }

        val distance = distanceTo(scanBox)
        if (!encoding.get() && keyframes.size < MAX_PHOTOS && distance in MIN_DISTANCE..MAX_DISTANCE && isSteady(now) &&
            boxCentreInView(camera, pose, scanBox) && synchronized(scanLock) { selector.isNew(toCamera) }
        ) {
            val image = try {
                frame.acquireCameraImage()
            } catch (e: NotYetAvailableException) {
                null
            } catch (e: Exception) {
                Log.w(TAG, "Camera image unavailable", e)
                null
            }
            if (image != null) {
                val nv21 = try {
                    CameraImages.copyNv21(image)
                } finally {
                    image.close()
                }
                val intrinsics = intrinsicsOf(camera, texture = false).scaledTo(nv21.width, nv21.height)
                synchronized(scanLock) {
                    selector.add(toCamera)
                    coverage.mark(cell)
                }
                encoding.set(true)
                encoder.execute {
                    try {
                        keyframes += Keyframe(nv21.toJpeg(JPEG_QUALITY), nv21.width, nv21.height, intrinsics, pose)
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't encode a scan photo", e)
                    } finally {
                        encoding.set(false)
                    }
                }
            }
        }
    }

    private fun acquireDepth(frame: Frame, camera: Camera, pose: CameraPose): DepthFrame? {
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Depth image unavailable", e)
            return null
        }
        return try {
            // Depth maps share the field of view of the camera texture (see ARCore's raw depth sample).
            DepthFrame(image.width, image.height, CameraImages.copyDepth(image), intrinsicsOf(camera, texture = true).scaledTo(image.width, image.height), pose)
        } finally {
            image.close()
        }
    }

    private fun intrinsicsOf(camera: Camera, texture: Boolean): Intrinsics {
        val source = if (texture) camera.textureIntrinsics else camera.imageIntrinsics
        val focal = source.focalLength
        val principal = source.principalPoint
        val size = source.imageDimensions
        return Intrinsics(focal[0], focal[1], principal[0], principal[1], size[0], size[1])
    }

    private fun boxCentreInView(camera: Camera, pose: CameraPose, scanBox: ScanBox): Boolean {
        val intrinsics = intrinsicsOf(camera, texture = false)
        if (!pose.project(scanBox.centerX, scanBox.centerY, scanBox.centerZ, intrinsics, projected)) return false
        val marginX = intrinsics.width * 0.15f
        val marginY = intrinsics.height * 0.15f
        return projected[0] in marginX..(intrinsics.width - marginX) && projected[1] in marginY..(intrinsics.height - marginY)
    }

    private fun distanceTo(scanBox: ScanBox): Float {
        val dx = poseMatrix[12] - scanBox.centerX
        val dy = poseMatrix[13] - scanBox.centerY
        val dz = poseMatrix[14] - scanBox.centerZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Photos are only taken while the phone turns and moves slowly, so they are sharp. */
    private fun isSteady(now: Long): Boolean {
        val forward = previousForward ?: return false
        val position = previousPosition ?: return false
        val seconds = (now - previousFrameAt) / 1000f
        if (seconds <= 0f || seconds > 0.5f) return false
        val dot = (-poseMatrix[8] * forward[0] - poseMatrix[9] * forward[1] - poseMatrix[10] * forward[2]).coerceIn(-1f, 1f)
        val turn = Math.toDegrees(acos(dot).toDouble()).toFloat() / seconds
        val dx = poseMatrix[12] - position[0]
        val dy = poseMatrix[13] - position[1]
        val dz = poseMatrix[14] - position[2]
        val speed = sqrt(dx * dx + dy * dy + dz * dz) / seconds
        return turn < MAX_TURN_DEG_PER_S && speed < MAX_SPEED_M_PER_S
    }

    private fun rememberMotion(now: Long) {
        previousForward = floatArrayOf(-poseMatrix[8], -poseMatrix[9], -poseMatrix[10])
        previousPosition = floatArrayOf(poseMatrix[12], poseMatrix[13], poseMatrix[14])
        previousFrameAt = now
    }

    private fun drawOverlay(overlay: OverlayRenderer) {
        val scanBox = box ?: return
        val scanning = phase == ScanPhase.SCANNING || phase == ScanPhase.BUILDING
        // Box edges.
        val x0 = scanBox.minX
        val x1 = scanBox.minX + scanBox.size
        val y0 = scanBox.bottomY
        val y1 = scanBox.bottomY + scanBox.size
        val z0 = scanBox.minZ
        val z1 = scanBox.minZ + scanBox.size
        val corners = arrayOf(
            floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1),
            floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1),
        )
        val edges = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        val lineColor = if (scanning) floatArrayOf(0.36f, 0.89f, 0.61f, 0.7f) else floatArrayOf(0.31f, 0.89f, 1f, 0.95f)
        var count = 0
        for (corner in edges) count = putVertex(count, corners[corner], lineColor)
        overlay.draw(GLES30.GL_LINES, overlayVertices, count, viewProjection)
        // Many GPUs only draw lines one pixel wide, so dots along the edges keep the box easy to see.
        count = 0
        for (edge in edges.indices step 2) {
            val from = corners[edges[edge]]
            val to = corners[edges[edge + 1]]
            for (step in 0 until EDGE_DOTS) {
                val t = step.toFloat() / EDGE_DOTS
                for (axis in 0..2) dot[axis] = from[axis] + (to[axis] - from[axis]) * t
                count = putVertex(count, dot, lineColor)
            }
        }
        overlay.draw(GLES30.GL_POINTS, overlayVertices, count, viewProjection, pointSize = 10f)
        if (!scanning) return
        // Coverage dome: one dot per direction, green once photographed.
        count = 0
        val radius = scanBox.size * 0.85f
        val covered = synchronized(scanLock) { coverage.covered.copyOf() }
        for (cell in 0 until CoverageTracker.CELLS) {
            val elevation: Double
            val azimuth: Double
            if (cell == CoverageTracker.TOP_CELL) {
                elevation = 88.0
                azimuth = 0.0
            } else {
                elevation = RING_ELEVATIONS[cell / CoverageTracker.SEGMENTS]
                azimuth = (cell % CoverageTracker.SEGMENTS + 0.5) * 360.0 / CoverageTracker.SEGMENTS
            }
            val e = Math.toRadians(elevation)
            val a = Math.toRadians(azimuth)
            val position = floatArrayOf(
                scanBox.centerX + (radius * cos(e) * sin(a)).toFloat(),
                scanBox.centerY + (radius * sin(e)).toFloat(),
                scanBox.centerZ + (radius * cos(e) * cos(a)).toFloat(),
            )
            count = putVertex(count, position, if (covered[cell]) COVERED else MISSING)
        }
        overlay.draw(GLES30.GL_POINTS, overlayVertices, count, viewProjection, pointSize = 26f)
    }

    private fun putVertex(index: Int, position: FloatArray, color: FloatArray): Int {
        val base = index * 7
        overlayVertices[base] = position[0]
        overlayVertices[base + 1] = position[1]
        overlayVertices[base + 2] = position[2]
        overlayVertices[base + 3] = color[0]
        overlayVertices[base + 4] = color[1]
        overlayVertices[base + 5] = color[2]
        overlayVertices[base + 6] = color[3]
        return index + 1
    }

    private fun publish(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPublishAt < PUBLISH_INTERVAL_MS) return
        lastPublishAt = now
        _status.value = synchronized(scanLock) {
            ScanStatus(
                phase = phase,
                trackingProblem = trackingProblem,
                boxSize = boxSize,
                coverage = coverage.covered.copyOf(),
                coverageFraction = coverage.fraction,
                nextStep = coverage.nextStep(),
                phoneAzimuth = phoneAzimuth,
                phoneRing = phoneRing,
                photos = keyframes.size,
                depthFrames = depthFrames.get(),
                message = message,
            )
        }
    }

    private fun fail(reason: String) {
        phase = ScanPhase.FAILED
        message = reason
        publish(force = true)
    }

    private fun openSession(): Session {
        val created = Session(context)
        if (!created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            created.close()
            throw IllegalStateException("this phone's ARCore has no depth support, which scanning needs")
        }
        chooseCameraConfig(created)
        val config = Config(created)
            .setDepthMode(Config.DepthMode.AUTOMATIC)
            .setFocusMode(Config.FocusMode.AUTO)
            .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            .setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL)
            .setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        created.configure(config)
        return created
    }

    /** Sharper scan photos: the largest camera image up to 1920 wide whose shape matches the preview texture. */
    private fun chooseCameraConfig(session: Session) {
        val filter = CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK)
        val candidates = session.getSupportedCameraConfigs(filter)
        val best = candidates
            .filter { it.imageSize.width <= 1920 }
            .filter {
                val image = it.imageSize.width.toFloat() / it.imageSize.height
                val texture = it.textureSize.width.toFloat() / it.textureSize.height
                abs(image - texture) < 0.01f
            }
            .maxByOrNull { it.imageSize.width * it.imageSize.height }
        if (best != null) session.cameraConfig = best
    }

    private fun decode(photo: Keyframe): KeyframeImage? {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bitmap = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, options) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = KeyframeImage(bitmap.width, bitmap.height, pixels, photo.intrinsics.scaledTo(bitmap.width, bitmap.height), photo.pose)
        bitmap.recycle()
        return image
    }

    private fun trackingMessage(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on more light."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Move the phone more slowly."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at a surface with more detail. A newspaper under the object helps."
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera isn't available."
        else -> "Move the phone slowly so it can find its position."
    }

    private companion object {
        const val TAG = "Reality3DScan"
        const val FUSION_THREADS = 4
        const val DEPTH_INTERVAL_MS = 250L
        const val PUBLISH_INTERVAL_MS = 150L
        const val MAX_PHOTOS = 150
        const val JPEG_QUALITY = 90
        const val MIN_DISTANCE = 0.12f
        const val MAX_DISTANCE = 3f
        const val MAX_TURN_DEG_PER_S = 45f
        const val MAX_SPEED_M_PER_S = 0.35f
        const val EDGE_DOTS = 12
        val RING_ELEVATIONS = doubleArrayOf(17.5, 42.5, 67.5)
        val COVERED = floatArrayOf(0.36f, 0.89f, 0.61f, 0.95f)
        val MISSING = floatArrayOf(1f, 1f, 1f, 0.55f)
    }
}
