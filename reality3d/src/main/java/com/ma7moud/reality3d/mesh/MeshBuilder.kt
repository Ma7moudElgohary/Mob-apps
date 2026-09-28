package com.ma7moud.reality3d.mesh

import com.ma7moud.reality3d.depth.DepthMap
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns one photo's depth map and subject mask into a textured triangle mesh.
 *
 * A vertex grid is laid over the photo and only the subject's vertices are kept. Their depth comes from:
 *  - relative depth (Depth Anything V2), normalised inside the subject, which tilts and bends the model and adds relief;
 *  - "inflation" ([Inflation]), which puffs the silhouette up so every part gets a rounded thickness
 *    that matches how wide it is.
 * In solid mode a mirrored back surface shares the silhouette vertices with the front, so the mesh is
 * closed (watertight) and can be 3D printed.
 */
object MeshBuilder {

    private const val MASK_THRESHOLD = 0.5f
    private const val MIN_CELLS = 8
    private const val MIN_SUBJECT_VERTICES = 24
    private const val MIN_COMPONENT_VERTICES = 12
    private const val MIN_COMPONENT_FRACTION = 0.02f
    private const val MAX_HOLE_FRACTION = 0.01f

    /** Relief, in subject lengths, for the subject's whole normalised depth range at strength 1. */
    private const val DEPTH_RELIEF = 0.3f

    /** A nearly flat subject is not stretched to more than this share of the photo's depth range. */
    private const val MIN_RELATIVE_RANGE = 0.25f

    /** Depth models smear depth across edges over about this many depth-map pixels. */
    private const val DEPTH_EDGE_BLUR_PX = 4f

    /** Depth is low-passed over this share of a grid cell before sampling, against aliasing. */
    private const val PREFILTER_CELLS = 0.5f
    private const val BACKGROUND_WEIGHT = 1e-3f

    /** Steepest relief allowed between neighbouring grid vertices (rise over run; 4 is about 76°). */
    private const val MAX_RELIEF_SLOPE = 4f
    private const val SLOPE_ITERATIONS = 300

    fun build(
        depth: DepthMap,
        mask: SubjectMask?,
        imageWidth: Int,
        imageHeight: Int,
        settings: MeshSettings = MeshSettings(),
    ): Mesh3D {
        require(imageWidth > 0 && imageHeight > 0) { "Empty photo" }
        val grid = gridFor(imageWidth.toFloat() / imageHeight, settings.detail.gridCells)
        val cols = grid.cols
        val rows = grid.rows
        val n = grid.count

        val confidence = sampleConfidence(mask, grid)
        val foreground = BooleanArray(n) { confidence[it] >= MASK_THRESHOLD }
        var isolated = mask != null && cleanUp(foreground, grid)
        var triangles = if (isolated) triangulate(foreground, grid) else IntArray(0)
        if (triangles.isEmpty()) {
            // No usable subject: fall back to the whole photo.
            isolated = false
            foreground.fill(true)
            confidence.fill(1f)
            triangles = triangulate(foreground, grid)
        }
        val topology = topology(triangles, grid)
        val used = topology.used
        val boundary = topology.boundary

        // Vertex positions in grid units; silhouette vertices move onto the mask's 50% contour.
        val gx = FloatArray(n) { (it % cols).toFloat() }
        val gy = FloatArray(n) { (it / cols).toFloat() }
        if (isolated) snapBoundary(gx, gy, topology, foreground, confidence, grid)

        // Depth per vertex, with the smeared rim replaced by values from further inside.
        val filtered = prefilterDepth(depth, if (isolated) mask else null, depth.photoWidth / (cols - 1))
        val raw = FloatArray(n)
        var usedCount = 0
        for (i in 0 until n) {
            if (!used[i]) continue
            raw[i] = filtered.sample(gx[i] / (cols - 1), gy[i] / (rows - 1))
            usedCount++
        }
        if (isolated) {
            val band = ceil(DEPTH_EDGE_BLUR_PX * max((cols - 1f) / depth.photoWidth, (rows - 1f) / depth.photoHeight))
                .toInt().coerceIn(2, 8)
            extendInteriorDepth(raw, topology, grid, band)
        }
        val middle = middleSurface(raw, used, usedCount, depth, settings.depthStrength)

        // Rounded thickness from the silhouette.
        val heights = Inflation.heights(BooleanArray(n) { used[it] && !boundary[it] }, cols, rows)
        if (settings.profile == ShapeProfile.BOXY) {
            val highest = heights.max()
            if (highest > 0f) for (i in 0 until n) heights[i] = highest * sqrt(heights[i] / highest)
        }

        // Scale so the subject's longest side in the photo plane is 1, centred on the origin.
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            if (!used[i]) continue
            minX = min(minX, gx[i])
            maxX = max(maxX, gx[i])
            minY = min(minY, gy[i])
            maxY = max(maxY, gy[i])
        }
        val scale = 1f / max(max(maxX - minX, maxY - minY), 1e-3f)
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2

        // Where one part hides another the depth jumps; spreading such jumps over a few cells makes them
        // read as a fold instead of saw-tooth fins along the grid.
        limitSlopes(middle, used, cols, rows, MAX_RELIEF_SLOPE * scale)

        val frontIndex = IntArray(n) { -1 }
        var frontCount = 0
        for (i in 0 until n) if (used[i]) frontIndex[i] = frontCount++
        val backIndex = IntArray(n) { -1 }
        var backCount = 0
        // Chords: inner edges of the front whose two ends are both on the silhouette. A mirrored copy would
        // coincide with the front one, so the back triangles next to a chord are split at its midpoint.
        val chords = HashMap<Long, Int>()
        if (settings.solid) {
            for (i in 0 until n) if (used[i] && !boundary[i]) backIndex[i] = frontCount + backCount++
            for (t in triangles.indices step 3) {
                for (e in 0 until 3) {
                    val p = triangles[t + e]
                    val q = triangles[t + (e + 1) % 3]
                    if (boundary[p] && boundary[q] && topology.uses(p, q) == 2) {
                        chords.getOrPut(edgeKey(p, q)) { frontCount + backCount + chords.size }
                    }
                }
            }
        }
        val vertexCount = frontCount + backCount + chords.size
        val positions = FloatArray(vertexCount * 3)
        val uvs = FloatArray(vertexCount * 2)
        for (i in 0 until n) {
            if (!used[i]) continue
            val x = (gx[i] - centerX) * scale
            val y = -(gy[i] - centerY) * scale
            val u = gx[i] / (cols - 1)
            val v = gy[i] / (rows - 1)
            val half = settings.thickness * heights[i] * scale
            putVertex(positions, uvs, frontIndex[i], x, y, middle[i] + half, u, v)
            if (backIndex[i] >= 0) putVertex(positions, uvs, backIndex[i], x, y, middle[i] - half, u, v)
        }
        for ((key, index) in chords) {
            val p = frontIndex[(key ushr 32).toInt()]
            val q = frontIndex[(key and 0xFFFFFFFFL).toInt()]
            putVertex(
                positions, uvs, index,
                (positions[p * 3] + positions[q * 3]) / 2,
                (positions[p * 3 + 1] + positions[q * 3 + 1]) / 2,
                (positions[p * 3 + 2] + positions[q * 3 + 2]) / 2,
                (uvs[p * 2] + uvs[q * 2]) / 2,
                (uvs[p * 2 + 1] + uvs[q * 2 + 1]) / 2,
            )
        }
        centerDepth(positions)

        val indices = IntArray(if (settings.solid) triangles.size * 5 else triangles.size)
        for (t in triangles.indices) indices[t] = frontIndex[triangles[t]]
        var count = triangles.size
        if (settings.solid) {
            // The back is the front mirrored and wound the other way; silhouette vertices are shared.
            fun back(vertex: Int) = if (backIndex[vertex] >= 0) backIndex[vertex] else frontIndex[vertex]
            val corner = IntArray(3)
            val middleOf = IntArray(3)
            for (t in triangles.indices step 3) {
                val mirrored = intArrayOf(triangles[t], triangles[t + 2], triangles[t + 1])
                for (k in 0 until 3) {
                    corner[k] = back(mirrored[k])
                    middleOf[k] = chords[edgeKey(mirrored[k], mirrored[(k + 1) % 3])] ?: -1
                }
                count = emitSplit(indices, count, corner, middleOf)
            }
        }
        val finalIndices = if (count == indices.size) indices else indices.copyOf(count)
        val mesh = Mesh3D(positions, vertexNormals(positions, finalIndices), uvs, finalIndices, settings.solid, isolated)
        val budget = settings.detail.budgetCells ?: return mesh
        // Same triangle count as a coarser grid would give, but spent where the surface bends.
        val ratio = budget.toFloat() / settings.detail.gridCells
        return MeshSimplifier.simplify(mesh, (mesh.triangleCount * ratio * ratio).roundToInt())
    }

    private fun edgeKey(a: Int, b: Int): Long = (min(a, b).toLong() shl 32) or max(a, b).toLong()

    /**
     * Low-passes the depth to the mesh's resolution (a Gaussian over about half a cell), so detail finer
     * than a cell doesn't alias into stripes and spikes. With a mask only subject pixels are averaged,
     * and background pixels next to the subject take on the subject's depth.
     */
    private fun prefilterDepth(depth: DepthMap, mask: SubjectMask?, pixelsPerCell: Float): DepthMap {
        val sigma = PREFILTER_CELLS * pixelsPerCell
        if (sigma < 0.6f) return depth
        val w = depth.width
        val h = depth.height
        val spanX = (depth.right - depth.left) / max(1, w - 1)
        val spanY = (depth.bottom - depth.top) / max(1, h - 1)
        val weight = FloatArray(w * h) { i ->
            if (mask == null || mask.sample(depth.left + (i % w) * spanX, depth.top + (i / w) * spanY) >= MASK_THRESHOLD) 1f else BACKGROUND_WEIGHT
        }
        val weighted = FloatArray(w * h) { depth.values[it] * weight[it] }
        val radius = ceil(3f * sigma).toInt()
        val kernel = FloatArray(2 * radius + 1) { val d = it - radius; kotlin.math.exp(-d * d / (2f * sigma * sigma)) }
        val numerator = blurSeparable(weighted, w, h, kernel)
        val denominator = blurSeparable(weight, w, h, kernel)
        val values = FloatArray(w * h) { numerator[it] / max(denominator[it], 1e-12f) }
        return DepthMap(w, h, values, depth.left, depth.top, depth.right, depth.bottom)
    }

    private fun blurSeparable(values: FloatArray, w: Int, h: Int, kernel: FloatArray): FloatArray {
        val radius = kernel.size / 2
        val horizontal = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * values[row + (x + k - radius).coerceIn(0, w - 1)]
                horizontal[row + x] = sum
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * horizontal[(y + k - radius).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = sum
            }
        }
        return out
    }

    /**
     * Relaxes [relief] until no two neighbouring grid vertices differ by more than [maxStep], moving both
     * ends of a too-steep edge towards each other. Gentle relief is left as it is.
     */
    internal fun limitSlopes(relief: FloatArray, used: BooleanArray, cols: Int, rows: Int, maxStep: Float, iterations: Int = SLOPE_ITERATIONS) {
        val delta = FloatArray(relief.size)
        repeat(iterations) {
            delta.fill(0f)
            var steep = false
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val a = y * cols + x
                    if (!used[a]) continue
                    if (x + 1 < cols && relax(relief, delta, used, a, a + 1, maxStep)) steep = true
                    if (y + 1 < rows && relax(relief, delta, used, a, a + cols, maxStep)) steep = true
                }
            }
            if (!steep) return
            for (i in relief.indices) relief[i] += delta[i]
        }
    }

    private fun relax(relief: FloatArray, delta: FloatArray, used: BooleanArray, a: Int, b: Int, maxStep: Float): Boolean {
        if (!used[b]) return false
        val difference = relief[a] - relief[b]
        val excess = abs(difference) - maxStep
        if (excess <= 0f) return false
        val shift = if (difference > 0f) 0.25f * excess else -0.25f * excess
        delta[a] -= shift
        delta[b] += shift
        return true
    }

    /**
     * Writes the counter-clockwise triangle [corner] into [out] at [start], split at the midpoints of
     * the edges that have one ([middleOf] k is the midpoint of edge k → k+1, or -1). Returns the new end.
     */
    private fun emitSplit(out: IntArray, start: Int, corner: IntArray, middleOf: IntArray): Int {
        var k = start
        fun add(p: Int, q: Int, r: Int) {
            out[k++] = p
            out[k++] = q
            out[k++] = r
        }
        when (middleOf.count { it >= 0 }) {
            0 -> add(corner[0], corner[1], corner[2])
            1 -> {
                val s = middleOf.indexOfFirst { it >= 0 }
                val p = corner[s]
                val q = corner[(s + 1) % 3]
                val r = corner[(s + 2) % 3]
                val m = middleOf[s]
                add(p, m, r)
                add(m, q, r)
            }
            2 -> {
                // Rotate so the edge without a midpoint is r → p.
                val s = (middleOf.indexOfFirst { it < 0 } + 1) % 3
                val p = corner[s]
                val q = corner[(s + 1) % 3]
                val r = corner[(s + 2) % 3]
                val m1 = middleOf[s]
                val m2 = middleOf[(s + 1) % 3]
                add(m1, q, m2)
                add(p, m1, m2)
                add(p, m2, r)
            }
            else -> {
                val m1 = middleOf[0]
                val m2 = middleOf[1]
                val m3 = middleOf[2]
                add(corner[0], m1, m3)
                add(m1, corner[1], m2)
                add(m3, m2, corner[2])
                add(m1, m2, m3)
            }
        }
        return k
    }

    private fun gridFor(aspect: Float, cells: Int): Grid = if (aspect >= 1f) {
        Grid(cells + 1, (cells / aspect).roundToInt().coerceAtLeast(MIN_CELLS) + 1)
    } else {
        Grid((cells * aspect).roundToInt().coerceAtLeast(MIN_CELLS) + 1, cells + 1)
    }

    /** Mask confidence per grid vertex: an average over the vertex's cell, or bilinear for small masks. */
    private fun sampleConfidence(mask: SubjectMask?, grid: Grid): FloatArray {
        val cols = grid.cols
        val rows = grid.rows
        if (mask == null) return FloatArray(grid.count) { 1f }
        if (mask.width < cols || mask.height < rows) {
            return FloatArray(grid.count) { mask.sample((it % cols).toFloat() / (cols - 1), (it / cols).toFloat() / (rows - 1)) }
        }
        val sum = FloatArray(grid.count)
        val hits = IntArray(grid.count)
        val sx = (cols - 1).toFloat() / (mask.width - 1)
        val sy = (rows - 1).toFloat() / (mask.height - 1)
        for (y in 0 until mask.height) {
            val rowStart = (y * sy).roundToInt() * cols
            val maskStart = y * mask.width
            for (x in 0 until mask.width) {
                val i = rowStart + (x * sx).roundToInt()
                sum[i] += mask.confidence[maskStart + x]
                hits[i]++
            }
        }
        return FloatArray(grid.count) { if (hits[it] > 0) sum[it] / hits[it] else 0f }
    }

    /** Drops specks and fills pinholes. Returns false when no usable subject is left. */
    private fun cleanUp(foreground: BooleanArray, grid: Grid): Boolean {
        val n = grid.count
        val label = IntArray(n) { -1 }
        val sizes = ArrayList<Int>()
        val queue = IntArray(n)
        for (start in 0 until n) {
            if (!foreground[start] || label[start] >= 0) continue
            val id = sizes.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = id
            while (head < tail) {
                grid.forEach4(queue[head++]) { j ->
                    if (foreground[j] && label[j] < 0) {
                        label[j] = id
                        queue[tail++] = j
                    }
                }
            }
            sizes += tail
        }
        val largest = sizes.maxOrNull() ?: return false
        val minSize = max(MIN_COMPONENT_VERTICES, (largest * MIN_COMPONENT_FRACTION).toInt())
        var subject = 0
        for (i in 0 until n) {
            if (!foreground[i]) continue
            if (sizes[label[i]] < minSize) foreground[i] = false else subject++
        }
        if (subject < MIN_SUBJECT_VERTICES) return false

        // Background regions that never reach the photo's border and are tiny are pinholes.
        label.fill(-1)
        val maxHole = max(4, (subject * MAX_HOLE_FRACTION).toInt())
        for (start in 0 until n) {
            if (foreground[start] || label[start] >= 0) continue
            var head = 0
            var tail = 0
            var touchesBorder = false
            queue[tail++] = start
            label[start] = 0
            while (head < tail) {
                val i = queue[head++]
                if (grid.onBorder(i)) touchesBorder = true
                grid.forEach4(i) { j ->
                    if (!foreground[j] && label[j] < 0) {
                        label[j] = 0
                        queue[tail++] = j
                    }
                }
            }
            if (!touchesBorder && tail <= maxHole) for (k in 0 until tail) foreground[queue[k]] = true
        }
        return true
    }

    /**
     * Two counter-clockwise triangles per grid cell inside the subject. Cells with one corner outside
     * keep the triangle that avoids it, which follows the silhouette more closely, and full cells avoid
     * a diagonal between two outline vertices (it would cut across a corner).
     */
    private fun triangulate(foreground: BooleanArray, grid: Grid): IntArray {
        val cols = grid.cols
        // Outline vertices: inside, with a 4-neighbour outside or on the photo's border.
        val outline = BooleanArray(grid.count) { i ->
            if (!foreground[i]) return@BooleanArray false
            var edge = grid.onBorder(i)
            grid.forEach4(i) { j -> if (!foreground[j]) edge = true }
            edge
        }
        val out = IntArray((grid.cols - 1) * (grid.rows - 1) * 6)
        var k = 0
        fun add(p: Int, q: Int, r: Int) {
            out[k++] = p
            out[k++] = q
            out[k++] = r
        }
        for (row in 0 until grid.rows - 1) {
            for (col in 0 until cols - 1) {
                val a = row * cols + col
                val b = a + 1
                val c = a + cols
                val d = c + 1
                val fa = foreground[a]
                val fb = foreground[b]
                val fc = foreground[c]
                val fd = foreground[d]
                val inside = (if (fa) 1 else 0) + (if (fb) 1 else 0) + (if (fc) 1 else 0) + (if (fd) 1 else 0)
                if (inside == 4) {
                    if (outline[b] && outline[c] && !(outline[a] && outline[d])) {
                        add(a, c, d)
                        add(a, d, b)
                    } else {
                        add(a, c, b)
                        add(b, c, d)
                    }
                } else if (inside == 3) {
                    when {
                        !fa -> add(b, c, d)
                        !fd -> add(a, c, b)
                        !fb -> add(a, c, d)
                        else -> add(a, d, b)
                    }
                }
            }
        }
        return out.copyOf(k)
    }

    private class Topology(
        val used: BooleanArray,
        val boundary: BooleanArray,
        /** Up to two silhouette neighbours per vertex. */
        val ring: IntArray,
        val ringDegree: IntArray,
        private val counts: IntArray,
        private val cols: Int,
    ) {
        /** How many triangles use the grid edge a–b. */
        fun uses(a: Int, b: Int): Int = counts[slot(a, b, cols, used.size)]
    }

    private fun slot(a: Int, b: Int, cols: Int, n: Int): Int {
        val kind = when (abs(a - b)) {
            1 -> 0
            cols -> 1
            cols - 1 -> 2
            cols + 1 -> 3
            else -> error("Not a grid edge")
        }
        return kind * n + min(a, b)
    }

    /** Finds the silhouette: edges used by only one triangle. */
    private fun topology(triangles: IntArray, grid: Grid): Topology {
        val n = grid.count
        val cols = grid.cols
        val counts = IntArray(4 * n)
        fun slot(a: Int, b: Int) = slot(a, b, cols, n)
        val used = BooleanArray(n)
        for (t in triangles.indices step 3) {
            val a = triangles[t]
            val b = triangles[t + 1]
            val c = triangles[t + 2]
            used[a] = true
            used[b] = true
            used[c] = true
            counts[slot(a, b)]++
            counts[slot(b, c)]++
            counts[slot(c, a)]++
        }
        val boundary = BooleanArray(n)
        val ring = IntArray(2 * n) { -1 }
        val ringDegree = IntArray(n)
        fun link(from: Int, to: Int) {
            if (ringDegree[from] < 2) ring[2 * from + ringDegree[from]] = to
            ringDegree[from]++
        }
        for (t in triangles.indices step 3) {
            for (e in 0 until 3) {
                val p = triangles[t + e]
                val q = triangles[t + (e + 1) % 3]
                if (counts[slot(p, q)] == 1) {
                    boundary[p] = true
                    boundary[q] = true
                    link(p, q)
                    link(q, p)
                }
            }
        }
        return Topology(used, boundary, ring, ringDegree, counts, cols)
    }

    /** Moves silhouette vertices (by at most half a cell) onto the mask's 50% contour, then smooths them. */
    private fun snapBoundary(
        gx: FloatArray,
        gy: FloatArray,
        topology: Topology,
        foreground: BooleanArray,
        confidence: FloatArray,
        grid: Grid,
    ) {
        val cols = grid.cols
        val rows = grid.rows
        for (i in 0 until grid.count) {
            if (!topology.boundary[i]) continue
            val x = i % cols
            val y = i / cols
            val own = confidence[i]
            var sx = 0f
            var sy = 0f
            var k = 0
            for (oy in -1..1) {
                for (ox in -1..1) {
                    val nx = x + ox
                    val ny = y + oy
                    if ((ox == 0 && oy == 0) || nx !in 0 until cols || ny !in 0 until rows) continue
                    val j = ny * cols + nx
                    if (foreground[j]) continue
                    val other = confidence[j]
                    val t = if (own - other > 1e-4f) ((own - MASK_THRESHOLD) / (own - other)).coerceIn(0f, 0.5f) else 0.25f
                    sx += t * ox
                    sy += t * oy
                    k++
                }
            }
            if (k > 0) {
                gx[i] += sx / k
                gy[i] += sy / k
            }
        }
        val px = gx.copyOf()
        val py = gy.copyOf()
        for (i in 0 until grid.count) {
            if (!topology.boundary[i] || topology.ringDegree[i] != 2) continue
            val a = topology.ring[2 * i]
            val b = topology.ring[2 * i + 1]
            gx[i] = 0.5f * px[i] + 0.25f * (px[a] + px[b])
            gy[i] = 0.5f * py[i] + 0.25f * (py[a] + py[b])
        }
    }

    /**
     * Depth models blur depth across the subject's outline, which would curl the rim towards the background.
     * Replaces the depth in a [band] of vertices along the silhouette by averages walked out from inside.
     */
    private fun extendInteriorDepth(values: FloatArray, topology: Topology, grid: Grid, band: Int) {
        val n = grid.count
        val distance = IntArray(n) { Int.MAX_VALUE }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) {
            if (topology.boundary[i]) {
                distance[i] = 0
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val d = distance[i]
            if (d >= band) continue
            grid.forEach8(i) { j ->
                if (topology.used[j] && distance[j] == Int.MAX_VALUE) {
                    distance[j] = d + 1
                    queue[tail++] = j
                }
            }
        }
        var hasInside = false
        for (i in 0 until n) if (topology.used[i] && distance[i] >= band) hasInside = true
        if (!hasInside) return
        for (d in band - 1 downTo 0) {
            for (k in 0 until tail) {
                val i = queue[k]
                if (distance[i] != d) continue
                var sum = 0f
                var count = 0
                grid.forEach8(i) { j ->
                    if (topology.used[j] && distance[j] > d) {
                        sum += values[j]
                        count++
                    }
                }
                if (count > 0) values[i] = sum / count
            }
        }
    }

    /** The depth-driven middle surface, in subject lengths, averaging zero over the subject. */
    private fun middleSurface(raw: FloatArray, used: BooleanArray, usedCount: Int, depth: DepthMap, strength: Float): FloatArray {
        val middle = FloatArray(raw.size)
        if (usedCount == 0 || strength <= 0f) return middle
        val subject = FloatArray(usedCount)
        var k = 0
        for (i in raw.indices) if (used[i]) subject[k++] = raw[i]
        subject.sort()
        val low = quantile(subject, 0.02f)
        val high = quantile(subject, 0.98f)
        val range = max(max(high - low, MIN_RELATIVE_RANGE * depth.robustRange), 1e-6f)
        var mean = 0.0
        for (i in raw.indices) {
            if (!used[i]) continue
            middle[i] = ((raw[i] - low) / range).coerceIn(0f, 1f)
            mean += middle[i]
        }
        val average = (mean / usedCount).toFloat()
        for (i in raw.indices) if (used[i]) middle[i] = strength * DEPTH_RELIEF * (middle[i] - average)
        return middle
    }

    private fun putVertex(positions: FloatArray, uvs: FloatArray, index: Int, x: Float, y: Float, z: Float, u: Float, v: Float) {
        positions[index * 3] = x
        positions[index * 3 + 1] = y
        positions[index * 3 + 2] = z
        uvs[index * 2] = u
        uvs[index * 2 + 1] = v
    }

    private fun centerDepth(positions: FloatArray) {
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (i in 2 until positions.size step 3) {
            low = min(low, positions[i])
            high = max(high, positions[i])
        }
        if (low > high) return
        val shift = (low + high) / 2
        for (i in 2 until positions.size step 3) positions[i] -= shift
    }

    internal fun vertexNormals(positions: FloatArray, indices: IntArray): FloatArray {
        val normals = FloatArray(positions.size)
        for (t in indices.indices step 3) {
            val a = indices[t] * 3
            val b = indices[t + 1] * 3
            val c = indices[t + 2] * 3
            val ux = positions[b] - positions[a]
            val uy = positions[b + 1] - positions[a + 1]
            val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]
            val vy = positions[c + 1] - positions[a + 1]
            val vz = positions[c + 2] - positions[a + 2]
            // Unnormalised cross product, so larger triangles count more.
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (p in intArrayOf(a, b, c)) {
                normals[p] += nx
                normals[p + 1] += ny
                normals[p + 2] += nz
            }
        }
        for (i in normals.indices step 3) {
            val length = sqrt(normals[i] * normals[i] + normals[i + 1] * normals[i + 1] + normals[i + 2] * normals[i + 2])
            if (length > 1e-12f) {
                normals[i] /= length
                normals[i + 1] /= length
                normals[i + 2] /= length
            } else {
                normals[i] = 0f
                normals[i + 1] = 0f
                normals[i + 2] = 1f
            }
        }
        return normals
    }

    private fun quantile(sorted: FloatArray, q: Float): Float =
        sorted[((sorted.size - 1) * q).roundToInt().coerceIn(0, sorted.size - 1)]

    private class Grid(val cols: Int, val rows: Int) {
        val count = cols * rows

        fun onBorder(i: Int): Boolean {
            val x = i % cols
            val y = i / cols
            return x == 0 || y == 0 || x == cols - 1 || y == rows - 1
        }

        inline fun forEach4(i: Int, action: (Int) -> Unit) {
            val x = i % cols
            val y = i / cols
            if (x > 0) action(i - 1)
            if (x < cols - 1) action(i + 1)
            if (y > 0) action(i - cols)
            if (y < rows - 1) action(i + cols)
        }

        inline fun forEach8(i: Int, action: (Int) -> Unit) {
            val x = i % cols
            val y = i / cols
            for (oy in -1..1) {
                val ny = y + oy
                if (ny < 0 || ny >= rows) continue
                for (ox in -1..1) {
                    val nx = x + ox
                    if ((ox == 0 && oy == 0) || nx < 0 || nx >= cols) continue
                    action(ny * cols + nx)
                }
            }
        }
    }
}
