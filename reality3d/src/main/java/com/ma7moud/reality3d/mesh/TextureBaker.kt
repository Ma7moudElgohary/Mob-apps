package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Prepares the photo as the model's texture.
 *
 * Around the subject the background is replaced by colours spread outwards from the subject (push-pull
 * filling), so texture filtering and mipmaps never pull background colours onto the model's edges. The
 * mask goes into alpha, for open reliefs that are cut out along it and for see-through gaps.
 */
object TextureBaker {

    private const val SUBJECT = 0.5f

    /** ARGB, not premultiplied: the photo with padded background colours and the mask in alpha. */
    fun bake(photo: IntArray, width: Int, height: Int, mask: SubjectMask?): IntArray {
        require(photo.size == width * height)
        if (mask == null) return IntArray(photo.size) { photo[it] or (0xFF shl 24) }
        val confidence = FloatArray(width * height)
        val sx = 1f / max(1, width - 1)
        val sy = 1f / max(1, height - 1)
        for (y in 0 until height) for (x in 0 until width) confidence[y * width + x] = mask.sample(x * sx, y * sy)

        // Pyramid of premultiplied colour sums, starting at half resolution: level 0 is the photo itself.
        val widths = ArrayList<Int>()
        val heights = ArrayList<Int>()
        val red = ArrayList<FloatArray>()
        val green = ArrayList<FloatArray>()
        val blue = ArrayList<FloatArray>()
        val weight = ArrayList<FloatArray>()
        var w = (width + 1) / 2
        var h = (height + 1) / 2
        run {
            val r = FloatArray(w * h)
            val g = FloatArray(w * h)
            val b = FloatArray(w * h)
            val n = FloatArray(w * h)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    if (confidence[i] < SUBJECT) continue
                    val p = (y / 2) * w + x / 2
                    val c = photo[i]
                    r[p] += ((c shr 16) and 0xFF).toFloat()
                    g[p] += ((c shr 8) and 0xFF).toFloat()
                    b[p] += (c and 0xFF).toFloat()
                    n[p] += 1f
                }
            }
            widths += w; heights += h; red += r; green += g; blue += b; weight += n
        }
        while (w > 1 || h > 1) {
            val pw = w
            val pr = red.last()
            val pg = green.last()
            val pb = blue.last()
            val pn = weight.last()
            val ph = h
            w = (w + 1) / 2
            h = (h + 1) / 2
            val r = FloatArray(w * h)
            val g = FloatArray(w * h)
            val b = FloatArray(w * h)
            val n = FloatArray(w * h)
            for (y in 0 until ph) {
                for (x in 0 until pw) {
                    val i = y * pw + x
                    if (pn[i] == 0f) continue
                    val p = (y / 2) * w + x / 2
                    r[p] += pr[i]
                    g[p] += pg[i]
                    b[p] += pb[i]
                    n[p] += pn[i]
                }
            }
            widths += w; heights += h; red += r; green += g; blue += b; weight += n
        }
        // Pull: empty cells take their parent's colour, from the top of the pyramid down.
        for (level in red.size - 2 downTo 0) {
            val cw = widths[level]
            val ch = heights[level]
            val parentWidth = widths[level + 1]
            val r = red[level]
            val g = green[level]
            val b = blue[level]
            val n = weight[level]
            val parentR = red[level + 1]
            val parentG = green[level + 1]
            val parentB = blue[level + 1]
            val parentN = weight[level + 1]
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    val i = y * cw + x
                    if (n[i] > 0f) continue
                    val p = (y / 2) * parentWidth + x / 2
                    if (parentN[p] <= 0f) continue
                    r[i] = parentR[p] / parentN[p]
                    g[i] = parentG[p] / parentN[p]
                    b[i] = parentB[p] / parentN[p]
                    n[i] = 1f
                }
            }
        }
        val halfWidth = widths[0]
        val r = red[0]
        val g = green[0]
        val b = blue[0]
        val n = weight[0]
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val alpha = (confidence[i].coerceIn(0f, 1f) * 255f).roundToInt()
                if (confidence[i] >= SUBJECT) {
                    out[i] = (alpha shl 24) or (photo[i] and 0xFFFFFF)
                    continue
                }
                val p = (y / 2) * halfWidth + x / 2
                val count = max(n[p], 1e-6f)
                val cr = if (n[p] > 0f) (r[p] / count).roundToInt().coerceIn(0, 255) else (photo[i] shr 16) and 0xFF
                val cg = if (n[p] > 0f) (g[p] / count).roundToInt().coerceIn(0, 255) else (photo[i] shr 8) and 0xFF
                val cb = if (n[p] > 0f) (b[p] / count).roundToInt().coerceIn(0, 255) else photo[i] and 0xFF
                out[i] = (alpha shl 24) or (cr shl 16) or (cg shl 8) or cb
            }
        }
        return out
    }

    /** The subject's box with a margin, as left, top, right, bottom (0..1); the whole photo without a mask. */
    fun subjectRegion(mask: SubjectMask?, margin: Float = 0.02f): FloatArray {
        val box = mask?.bounds() ?: return floatArrayOf(0f, 0f, 1f, 1f)
        return floatArrayOf(
            max(0f, box[0] - margin), max(0f, box[1] - margin),
            min(1f, box[2] + margin), min(1f, box[3] + margin),
        )
    }

    /** [mesh] with texture coordinates remapped into [region] (left, top, right, bottom of the old texture). */
    fun cropUvs(mesh: Mesh3D, region: FloatArray): Mesh3D {
        val uvs = mesh.uvs ?: return mesh
        val w = region[2] - region[0]
        val h = region[3] - region[1]
        val remapped = FloatArray(uvs.size) { i ->
            if (i % 2 == 0) (uvs[i] - region[0]) / w else (uvs[i] - region[1]) / h
        }
        return Mesh3D(mesh.positions, mesh.normals, remapped, mesh.indices, mesh.solid, mesh.subjectIsolated, mesh.colors, mesh.realScale)
    }
}
