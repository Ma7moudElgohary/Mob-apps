package com.ma7moud.reality3d.mesh

import kotlin.math.max

object MeshCleanup {
    fun clean(
        source: DepthMesh,
        minIslandFraction: Float = 0.01f,
        maxHoleEdges: Int = 20,
        smoothingIterations: Int = 1,
        smoothingStrength: Float = 0.18f,
    ): DepthMesh {
        if (source.triangleCount == 0) return source
        var mesh = removeTinyIslands(source, minIslandFraction)
        mesh = fillSmallHoles(mesh, maxHoleEdges)
        mesh = smoothInterior(mesh, smoothingIterations, smoothingStrength)
        return MeshMath.recalculateNormals(mesh)
    }

    fun boundaryEdgeRatio(mesh: DepthMesh): Float {
        if (mesh.indices.isEmpty()) return 1f
        val counts = edgeCounts(mesh)
        val boundary = counts.values.count { it == 1 }
        return boundary.toFloat() / counts.size.coerceAtLeast(1)
    }

    private fun removeTinyIslands(mesh: DepthMesh, minFraction: Float): DepthMesh {
        val triangleCount = mesh.triangleCount
        if (triangleCount <= 1) return mesh
        val vertexToTriangles = Array(mesh.vertexCount) { ArrayList<Int>() }
        for (t in 0 until triangleCount) {
            val base = t * 3
            vertexToTriangles[mesh.indices[base]].add(t)
            vertexToTriangles[mesh.indices[base + 1]].add(t)
            vertexToTriangles[mesh.indices[base + 2]].add(t)
        }
        val visited = BooleanArray(triangleCount)
        val components = ArrayList<IntArray>()
        for (start in 0 until triangleCount) {
            if (visited[start]) continue
            val queue = ArrayDeque<Int>()
            val component = ArrayList<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst()
                component.add(t)
                val base = t * 3
                repeat(3) { corner ->
                    val vertex = mesh.indices[base + corner]
                    vertexToTriangles[vertex].forEach { adjacent ->
                        if (!visited[adjacent]) {
                            visited[adjacent] = true
                            queue.add(adjacent)
                        }
                    }
                }
            }
            components.add(component.toIntArray())
        }
        if (components.size <= 1) return mesh
        val largest = components.maxOf { it.size }
        val threshold = max(2, (largest * minFraction.coerceIn(0f, 0.5f)).toInt())
        val keptTriangles = components.filter { it.size >= threshold }.flatMap { it.asIterable() }
        if (keptTriangles.size == triangleCount) return mesh
        return compact(mesh, keptTriangles)
    }

    private fun compact(mesh: DepthMesh, triangles: List<Int>): DepthMesh {
        val used = BooleanArray(mesh.vertexCount)
        triangles.forEach { t ->
            val base = t * 3
            used[mesh.indices[base]] = true
            used[mesh.indices[base + 1]] = true
            used[mesh.indices[base + 2]] = true
        }
        val remap = IntArray(mesh.vertexCount) { -1 }
        var newCount = 0
        used.forEachIndexed { index, yes -> if (yes) remap[index] = newCount++ }
        val positions = FloatArray(newCount * 3)
        val tex = FloatArray(newCount * 2)
        val colors = mesh.colors?.takeIf { it.size == mesh.vertexCount * 4 }?.let { FloatArray(newCount * 4) }
        for (old in 0 until mesh.vertexCount) {
            val new = remap[old]
            if (new < 0) continue
            System.arraycopy(mesh.positions, old * 3, positions, new * 3, 3)
            if (mesh.texCoords.size >= old * 2 + 2) {
                System.arraycopy(mesh.texCoords, old * 2, tex, new * 2, 2)
            }
            colors?.let { System.arraycopy(requireNotNull(mesh.colors), old * 4, it, new * 4, 4) }
        }
        val indices = IntArray(triangles.size * 3)
        triangles.forEachIndexed { index, triangle ->
            val base = triangle * 3
            indices[index * 3] = remap[mesh.indices[base]]
            indices[index * 3 + 1] = remap[mesh.indices[base + 1]]
            indices[index * 3 + 2] = remap[mesh.indices[base + 2]]
        }
        return DepthMesh(
            positions = positions,
            texCoords = tex,
            indices = indices,
            colors = colors,
            unitsToMeters = mesh.unitsToMeters,
        )
    }

    private data class Edge(val a: Int, val b: Int) {
        companion object {
            fun of(a: Int, b: Int): Edge = if (a < b) Edge(a, b) else Edge(b, a)
        }
    }

    private fun edgeCounts(mesh: DepthMesh): Map<Edge, Int> {
        val counts = HashMap<Edge, Int>()
        var i = 0
        while (i + 2 < mesh.indices.size) {
            val a = mesh.indices[i]
            val b = mesh.indices[i + 1]
            val c = mesh.indices[i + 2]
            listOf(Edge.of(a, b), Edge.of(b, c), Edge.of(c, a)).forEach { edge ->
                counts[edge] = (counts[edge] ?: 0) + 1
            }
            i += 3
        }
        return counts
    }

    private fun fillSmallHoles(mesh: DepthMesh, maxHoleEdges: Int): DepthMesh {
        if (maxHoleEdges < 3) return mesh
        val boundaryEdges = edgeCounts(mesh).filterValues { it == 1 }.keys
        if (boundaryEdges.size < 3) return mesh
        val adjacency = HashMap<Int, MutableList<Int>>()
        boundaryEdges.forEach { edge ->
            adjacency.getOrPut(edge.a) { ArrayList() }.add(edge.b)
            adjacency.getOrPut(edge.b) { ArrayList() }.add(edge.a)
        }
        val unused = boundaryEdges.toMutableSet()
        val loops = ArrayList<List<Int>>()
        while (unused.isNotEmpty()) {
            val first = unused.first()
            val loop = ArrayList<Int>()
            loop.add(first.a)
            var previous = first.a
            var current = first.b
            unused.remove(first)
            var guard = 0
            while (guard++ < maxHoleEdges + 2) {
                loop.add(current)
                if (current == loop.first()) break
                val next = adjacency[current]
                    ?.firstOrNull { candidate -> candidate != previous && Edge.of(current, candidate) in unused }
                    ?: break
                unused.remove(Edge.of(current, next))
                previous = current
                current = next
            }
            if (loop.size >= 4 && loop.last() == loop.first() && loop.size - 1 <= maxHoleEdges) {
                loops.add(loop.dropLast(1))
            }
        }
        if (loops.isEmpty()) return mesh

        val positions = ArrayList<Float>(mesh.positions.size + loops.size * 3).apply {
            mesh.positions.forEach(::add)
        }
        val tex = ArrayList<Float>(mesh.texCoords.size + loops.size * 2).apply {
            mesh.texCoords.forEach(::add)
        }
        val colorSource = mesh.colors?.takeIf { it.size == mesh.vertexCount * 4 }
        val colors = colorSource?.let { source ->
            ArrayList<Float>(source.size + loops.size * 4).apply { source.forEach(::add) }
        }
        val indices = ArrayList<Int>(mesh.indices.size + loops.sumOf { it.size * 3 }).apply {
            mesh.indices.forEach(::add)
        }

        loops.forEach { loop ->
            var x = 0f
            var y = 0f
            var z = 0f
            var u = 0f
            var v = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            var a = 0f
            loop.forEach { vertex ->
                x += mesh.positions[vertex * 3]
                y += mesh.positions[vertex * 3 + 1]
                z += mesh.positions[vertex * 3 + 2]
                if (mesh.texCoords.size >= vertex * 2 + 2) {
                    u += mesh.texCoords[vertex * 2]
                    v += mesh.texCoords[vertex * 2 + 1]
                }
                colorSource?.let {
                    r += it[vertex * 4]
                    g += it[vertex * 4 + 1]
                    b += it[vertex * 4 + 2]
                    a += it[vertex * 4 + 3]
                }
            }
            val count = loop.size.toFloat()
            val center = positions.size / 3
            positions.add(x / count)
            positions.add(y / count)
            positions.add(z / count)
            tex.add(u / count)
            tex.add(v / count)
            colors?.let {
                it.add(r / count)
                it.add(g / count)
                it.add(b / count)
                it.add(if (a == 0f) 1f else a / count)
            }
            for (i in loop.indices) {
                indices.add(loop[i])
                indices.add(loop[(i + 1) % loop.size])
                indices.add(center)
            }
        }
        return DepthMesh(
            positions = positions.toFloatArray(),
            texCoords = tex.toFloatArray(),
            indices = indices.toIntArray(),
            colors = colors?.toFloatArray(),
            unitsToMeters = mesh.unitsToMeters,
        )
    }

    private fun smoothInterior(mesh: DepthMesh, iterations: Int, strength: Float): DepthMesh {
        if (iterations <= 0 || mesh.vertexCount < 3) return mesh
        val boundary = BooleanArray(mesh.vertexCount)
        edgeCounts(mesh).filterValues { it == 1 }.keys.forEach {
            boundary[it.a] = true
            boundary[it.b] = true
        }
        val neighbors = Array(mesh.vertexCount) { LinkedHashSet<Int>() }
        var i = 0
        while (i + 2 < mesh.indices.size) {
            val a = mesh.indices[i]
            val b = mesh.indices[i + 1]
            val c = mesh.indices[i + 2]
            neighbors[a].add(b); neighbors[a].add(c)
            neighbors[b].add(a); neighbors[b].add(c)
            neighbors[c].add(a); neighbors[c].add(b)
            i += 3
        }
        var positions = mesh.positions.copyOf()
        repeat(iterations.coerceIn(1, 5)) {
            val next = positions.copyOf()
            for (vertex in 0 until mesh.vertexCount) {
                if (boundary[vertex] || neighbors[vertex].isEmpty()) continue
                var ax = 0f
                var ay = 0f
                var az = 0f
                neighbors[vertex].forEach { n ->
                    ax += positions[n * 3]
                    ay += positions[n * 3 + 1]
                    az += positions[n * 3 + 2]
                }
                val count = neighbors[vertex].size.toFloat()
                val p = vertex * 3
                val s = strength.coerceIn(0f, 0.5f)
                next[p] = positions[p] * (1f - s) + (ax / count) * s
                next[p + 1] = positions[p + 1] * (1f - s) + (ay / count) * s
                next[p + 2] = positions[p + 2] * (1f - s) + (az / count) * s
            }
            positions = next
        }
        return mesh.copy(positions = positions)
    }
}
