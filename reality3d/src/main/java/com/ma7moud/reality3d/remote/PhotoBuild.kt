package com.ma7moud.reality3d.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ma7moud.reality3d.mesh.GlbReader
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.quality.QualityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** How much time to spend on the model: more takes longer and shows finer detail. */
enum class BuildQuality(val id: String, val label: String, val hint: String) {
    FAST("fast", "Fast", "A few minutes, less detail"),
    STANDARD("standard", "Standard", "The usual choice"),
    HIGH("high", "High", "Most detail, the slowest"),
}

/** What the photos show: one object standing somewhere, or a whole place. */
enum class BuildMode(val id: String, val label: String) {
    OBJECT("object", "One object"),
    SCENE("scene", "Whole scene"),
}

data class PhotoBuildOptions(val quality: BuildQuality = BuildQuality.STANDARD, val mode: BuildMode = BuildMode.OBJECT)

/**
 * What the computer's photo builder made: the mesh, its texture, and how big it is. [sizeKnown] is true when the
 * photos came with ARCore's positions (a scan), so the model is in real meters; otherwise only its shape is real.
 */
class PhotoModel(
    val mesh: Mesh3D,
    val texture: Bitmap?,
    val metersPerUnit: Float,
    val sizeKnown: Boolean,
    /** How many photos were sent and how many the builder could place, when it says. */
    val photos: Int?,
    val placed: Int?,
)

/** How well the photos worked out: how many the builder could use, and whether there were enough of them. */
fun PhotoModel.quality(sent: Int): QualityReport {
    val used = (placed ?: sent).coerceIn(0, maxOf(sent, 1))
    val share = used.toFloat() / maxOf(sent, 1)
    val issues = ArrayList<String>()
    if (share < 0.8f) issues += "Only $used of $sent photos could be placed. Move a little between photos, keep the object in view, and keep the light steady."
    if (sent < 30) issues += "Only $sent photos. About 40 or more, from all around, give a cleaner and more detailed model."
    val score = (100f * share.coerceIn(0f, 1f) * (0.55f + 0.45f * (sent / 40f).coerceAtMost(1f))).roundToInt().coerceIn(0, 100)
    return QualityReport(score, issues)
}

/** Sends a zip of photos to the computer's photo builder and turns the GLB that comes back into a [PhotoModel]. */
class PhotoModelBuilder(private val remote: RemoteServer) {

    /** Asks the server what it has: its photo builder, or a readable reason why there is none. */
    suspend fun checkServer(settings: RemoteSettings): RemoteEngine {
        val engine = remote.health(settings).engines.firstOrNull { it.kind == "photos" }
            ?: throw RemoteException("This server has no photo builder yet. Update the reality3d-server folder on the computer and start it again.")
        if (!engine.available) throw RemoteException(engine.why ?: "The photo builder isn't set up on the computer.")
        return engine
    }

    suspend fun build(
        settings: RemoteSettings,
        photos: File,
        options: PhotoBuildOptions,
        onProgress: (fraction: Float?, message: String?) -> Unit,
    ): PhotoModel {
        val bytes = remote.buildFromPhotos(settings, photos, options, onProgress)
        onProgress(1f, "Opening the model")
        return withContext(Dispatchers.Default) { open(bytes) }
    }

    companion object {
        /** No texture is kept larger than this: bigger ones don't fit in a phone's memory as bitmaps. */
        const val MAX_TEXTURE = 4096

        /** A GLB from the photo builder, or from any other app. */
        fun open(glb: ByteArray): PhotoModel {
            val model = try {
                GlbReader.read(glb)
            } catch (e: GlbReader.FormatException) {
                throw RemoteException("The computer sent a model this app can't open (${e.message}).")
            }
            val texture = model.texture?.let { decodeTexture(it) }
            val known = model.info?.scaleKnown == true
            return PhotoModel(
                mesh = model.mesh,
                texture = texture,
                metersPerUnit = if (known) 1f else unitsAsMeters(model.mesh),
                sizeKnown = known,
                photos = model.info?.photos,
                placed = model.info?.placed,
            )
        }

        /**
         * How many meters a model unit probably is when nobody said: glTF is in meters, so a model whose longest
         * side is between a centimeter and thirty meters is taken as it is; anything else is shown 30 cm long.
         */
        fun unitsAsMeters(mesh: Mesh3D): Float {
            val longest = mesh.longestSide
            return if (longest in 0.01f..30f) 1f else DEFAULT_LONGEST_SIDE_METERS / longest.coerceAtLeast(1e-6f)
        }

        private const val DEFAULT_LONGEST_SIDE_METERS = com.ma7moud.reality3d.export.Exporter.DEFAULT_LONGEST_SIDE_METERS

        private fun decodeTexture(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_TEXTURE) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPremultiplied = false
            })
        }
    }
}
