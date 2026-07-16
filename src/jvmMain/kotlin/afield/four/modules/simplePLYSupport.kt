@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import afield.stdlib.linalg.Vector2f

import afield.Box
import afield.RunsAt
import afield.four.Renderer
import afield.four.buffers.defaultBuffer
import afield.four.util.Camera
import afield.logger.lprintln
import afield.macroRegistration
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.macros.MacroRegistry.callRewrite
import afield.stdlib.Vec3
import afield.stdlib._moduleHere
import afield.stdlib.macro_moduleHereWithDefaultShaders
import com.github.jelmerk.knn.DistanceFunctions
import com.github.jelmerk.knn.Item
import com.github.jelmerk.knn.hnsw.HnswIndex
import kotlinx.coroutines.DelicateCoroutinesApi
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memAllocInt
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

// the idea here is that this module can be customized for the (first?) model inside the PLY
fun Box.plyModelHere(p: Parameters.() -> Unit): Pair<ModulePointer, PlySupport.PlyModel> {
    throw MacroRegistry.MacroNotExpandedException("plyModelHere should not be callable directly, but should be expanded as a macro")
}

fun Box.plyPointCloudHere(p: Parameters.() -> Unit): Pair<ModulePointer, PlySupport.PlyModel> {
    throw MacroRegistry.MacroNotExpandedException("plyPointCloudHere should not be callable directly, but should be expanded as a macro")
}

@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_plyModelHere(args: List<String>): Insert? {
    return macro_moduleHereWithDefaultShaders(
        args, vertex = PlySupport.plyVertexShader, fragment = PlySupport.plyFragmentShader, resolvesToCall = "_plyModelHere"
    )
}

@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_plyPointCloudHere(args: List<String>): Insert? {
    return macro_moduleHereWithDefaultShaders(
        args, vertex = PlySupport.plyPointCloudVertexShader, fragment = PlySupport.plyPointCloudFragmentShader, resolvesToCall = "_plyPointCloudHere"
    )
}

fun Box._plyModelHere(
    filename: String, p: Parameters.() -> Unit = {}
): Pair<ModulePointer, PlySupport.PlyModel> {
    val renderer: Renderer = fromContext()
    val module = _moduleHere(parameters(renderer = renderer, p))
    val loader = PlySupport.loadPly(filename)
    return module to loader
}

fun Box._plyPointCloudHere(
    filename: String, pointSize: Float = 8.0f, p: Parameters.() -> Unit = {}
): Pair<ModulePointer, PlySupport.PlyModel> {
    val renderer: Renderer = fromContext()
    val module = _moduleHere(parameters(renderer = renderer) {
        p()
        primitiveType = triangles  // Changed from points to triangles for quad rendering
    })
    val loader = PlySupport.loadPly(filename)
    return module to loader
}

@RunsAt(macroRegistration)
fun registerPLYLoader() {
    callRewrite["plyModelHere"] = { box, id, args ->
        box.macro_plyModelHere(args)
    }
    callRewrite["plyPointCloudHere"] = { box, id, args ->
        box.macro_plyPointCloudHere(args)
    }
}

object PlySupport {

    // ------------------------------------------------------------------------
    // Public data model
    // ------------------------------------------------------------------------

    /**
     * PLY mesh buffers containing vertex and optional index data.
     *
     * Note: The ByteBuffer and IntBuffer are allocated with LWJGL's memAlloc
     * and should be freed when no longer needed. Typically, these buffers are
     * transferred to GPU via defaultBuffer and can be freed after the transfer.
     * The buffers are managed by the Vulkan buffer system when used with applyToMesh.
     */
    data class PlyMeshBuffers(
        val vertexData: ByteBuffer,  // tightly packed floats: [pos.xyz][normal.xyz][color.rgba] = 10 floats
        val indexData: java.nio.IntBuffer?,   // 32-bit indices (null for point clouds)
        val vertexCount: Int,
        val indexCount: Int,
        val hasColors: Boolean,
        val hasNormals: Boolean,
        val isPointCloud: Boolean
    )

    data class PlyModel(
        val buffers: PlyMeshBuffers,
        val name: String
    ) {
        fun applyToMesh(m: Mesh, camera: Camera = fromContext(), pointSize: Float = 8.0f) {
            m.initializePushConstantsFromModule(m.inside)
            m.push["modelView"] = camera.m()
            m.push["proj"] = camera.p()
            m.push["vertexOffset"] = 0
            m.push["indexOffset"] = 0
            m.push["vertexStride"] = 10
            m.push["materialBaseColor"] = Vector4f(1f, 1f, 1f, 1f)
            m.push["useVertexColor"] = if (buffers.hasColors) 1 else 0

            // Set point size for point cloud shaders (will be ignored if not present in shader)
            if (m.push.offsets.containsKey("pointSize")) {
                m.push["pointSize"] = pointSize
            }

            val vertexData = defaultBuffer {
                size = (buffers.vertexCount * 10).floats
            }.buffer

            m.push["vertexBuffer"] = vertexData

            vertexData.write { this.put(buffers.vertexData.asFloatBuffer()) }

            if (buffers.isPointCloud) {
                // For point clouds, create a simple sequential index buffer for compatibility
                val indexData = defaultBuffer {
                    size = buffers.vertexCount.ints
                }.buffer

                m.push["indexBuffer"] = indexData

                indexData.write {
                    for (n in 0 until buffers.vertexCount) {
                        this.putInt(n)
                    }
                }

                m.resize(buffers.vertexCount)
            } else {
                // For triangle meshes, use index buffer
                val indexData = defaultBuffer {
                    size = buffers.indexCount.ints
                }.buffer

                m.push["indexBuffer"] = indexData

                indexData.write {
                    val sourceIndices = buffers.indexData
                        ?: error("Internal error: indexData should not be null for non-point-cloud")
                    for (n in 0 until buffers.indexCount) {
                        this.putInt(sourceIndices.get(n))
                    }
                }

                m.resize(buffers.indexCount)
            }
        }

        /**
         * Apply point cloud data to mesh using quad-based rendering (6 vertices per point).
         * This version is for use with plyPointCloudVertexShader which generates quads in the vertex shader.
         *
         * @param viewportWidth Viewport width in pixels (required for proper quad sizing)
         * @param viewportHeight Viewport height in pixels (required for proper quad sizing)
         */
        fun applyToMeshAsQuads(m: Mesh, camera: Camera = fromContext(), pointSize: Float = 8.0f, viewportWidth: Float, viewportHeight: Float) {
            m.initializePushConstantsFromModule(m.inside)
            if (m.push.offsets.containsKey("modelView")) {
                m.push.bind("modelView") {
                    camera.m()
                }
            }
            m.push["proj"] =  camera.p()
            m.push["vertexOffset"] = 0
            m.push["indexOffset"] = 0
            m.push["vertexStride"] = 10
            m.push["materialBaseColor"] = Vector4f(1f, 1f, 1f, 1f)
            m.push["useVertexColor"] = if (buffers.hasColors) 1 else 0
            if (m.push.offsets.containsKey("pointSize")) {
                m.push["pointSize"] = pointSize
            }
            if (m.push.offsets.containsKey("viewportSize")) {
                m.push["viewportSize"] = Vector2f(viewportWidth, viewportHeight)
            }

            val vertexData = defaultBuffer {
                size = (buffers.vertexCount * 10).floats
            }.buffer

            m.push["vertexBuffer"] = vertexData

            vertexData.write { this.put(buffers.vertexData.asFloatBuffer()) }

            // Create index buffer for point data
            val indexData = defaultBuffer {
                size = buffers.vertexCount.ints
            }.buffer

            m.push["indexBuffer"] = indexData

            indexData.write {
                for (n in 0 until buffers.vertexCount) {
                    this.putInt(n)
                }
            }

            // Resize mesh to 6 times the vertex count
            // Each point generates 6 vertices (2 triangles with 3 vertices each forming a quad)
            m.resize(buffers.vertexCount * 6)
        }

        /**
         * Build a packed IntBuffer of K-nearest-neighbor indices for each vertex.
         *
         * Layout: [n0_k0, n0_k1, ... n0_k{K-1}, n1_k0, ...] (stride = K).
         * If K > (vertexCount - 1), the remaining entries are filled with -1.
         *
         * Note: The returned buffer is allocated with memAllocInt and should be freed
         * when no longer needed. Typical usage is to upload to GPU then free.
         */
        fun buildKnnIndexBuffer(k: Int): java.nio.IntBuffer {
            require(k > 0) { "k must be > 0" }

            val vertexCount = buffers.vertexCount
            val output = memAllocInt(vertexCount * k)

            if (vertexCount == 0) {
                output.flip()
                return output
            }

            val kEffective = when {
                vertexCount <= 1 -> 0
                k < vertexCount -> k
                else -> vertexCount - 1
            }

            // Extract positions (x,y,z) into a contiguous array for fast access.
            val positions = FloatArray(vertexCount * 3)
            val vertexFloats = buffers.vertexData.asFloatBuffer()
            val strideFloats = 10
            for (i in 0 until vertexCount) {
                val base = i * strideFloats
                val p = i * 3
                positions[p] = vertexFloats.get(base)
                positions[p + 1] = vertexFloats.get(base + 1)
                positions[p + 2] = vertexFloats.get(base + 2)
            }

            if (kEffective == 0) {
                repeat(vertexCount) {
                    repeat(k) { output.put(-1) }
                }
                output.flip()
                return output
            }

            val bestDistances = FloatArray(kEffective) { Float.POSITIVE_INFINITY }
            val bestIndices = IntArray(kEffective) { -1 }

            for (i in 0 until vertexCount) {
                bestDistances.fill(Float.POSITIVE_INFINITY)
                bestIndices.fill(-1)

                val pi = i * 3
                val ix = positions[pi]
                val iy = positions[pi + 1]
                val iz = positions[pi + 2]

                for (j in 0 until vertexCount) {
                    if (j == i) continue

                    val pj = j * 3
                    val dx = ix - positions[pj]
                    val dy = iy - positions[pj + 1]
                    val dz = iz - positions[pj + 2]
                    val distSq = dx * dx + dy * dy + dz * dz

                    if (distSq >= bestDistances[kEffective - 1]) continue

                    var pos = kEffective - 1
                    while (pos > 0 && distSq < bestDistances[pos - 1]) {
                        bestDistances[pos] = bestDistances[pos - 1]
                        bestIndices[pos] = bestIndices[pos - 1]
                        pos--
                    }

                    bestDistances[pos] = distSq
                    bestIndices[pos] = j
                }

                for (kk in 0 until kEffective) {
                    output.put(bestIndices[kk])
                }
                for (kk in kEffective until k) {
                    output.put(-1)
                }
            }

            output.flip()
            return output
        }

        private data class PointItem(
            private val idValue: Int,
            private val vectorValue: FloatArray
        ) : Item<Int, FloatArray> {
            override fun id(): Int = idValue
            override fun vector(): FloatArray = vectorValue
            override fun dimensions(): Int = vectorValue.size
        }

        /**
         * Build a packed IntBuffer of K-nearest-neighbor indices for each vertex using HNSW.
         *
         * Layout: [n0_k0, n0_k1, ... n0_k{K-1}, n1_k0, ...] (stride = K).
         * If K > (vertexCount - 1), the remaining entries are filled with -1.
         *
         * Note: The returned buffer is allocated with memAllocInt and should be freed
         * when no longer needed. Typical usage is to upload to GPU then free.
         */
        fun buildKnnIndexBufferApprox(
            k: Int,
            m: Int = 16,
            efConstruction: Int = 200,
            efSearch: Int = 200
        ): java.nio.IntBuffer {
            require(k > 0) { "k must be > 0" }
            require(m > 0) { "m must be > 0" }
            require(efConstruction > 0) { "efConstruction must be > 0" }
            require(efSearch > 0) { "efSearch must be > 0" }

            val vertexCount = buffers.vertexCount
            val output = memAllocInt(vertexCount * k)

            if (vertexCount == 0) {
                output.flip()
                return output
            }

            val kEffective = when {
                vertexCount <= 1 -> 0
                k < vertexCount -> k
                else -> vertexCount - 1
            }

            val vectors = Array(vertexCount) { FloatArray(3) }
            val vertexFloats = buffers.vertexData.asFloatBuffer()
            val strideFloats = 10
            for (i in 0 until vertexCount) {
                val base = i * strideFloats
                val vec = vectors[i]
                vec[0] = vertexFloats.get(base)
                vec[1] = vertexFloats.get(base + 1)
                vec[2] = vertexFloats.get(base + 2)
            }

            if (kEffective == 0) {
                repeat(vertexCount) {
                    repeat(k) { output.put(-1) }
                }
                output.flip()
                return output
            }

            val index: HnswIndex<Int, FloatArray, PointItem, Float> = HnswIndex
                .newBuilder(3, DistanceFunctions.FLOAT_EUCLIDEAN_DISTANCE, vertexCount)
                .withM(m)
                .withEfConstruction(efConstruction)
                .withEf(efSearch.coerceAtLeast(kEffective))
                .build()

            for (i in 0 until vertexCount) {
                index.add(PointItem(i, vectors[i]))
            }

            val queryK = (kEffective + 1).coerceAtMost(vertexCount)
            for (i in 0 until vertexCount) {
                val results = index.findNearest(vectors[i], queryK)
                var added = 0
                for (result in results) {
                    val id = result.item().id()
                    if (id == i) continue
                    output.put(id)
                    added++
                    if (added == kEffective) break
                }
                while (added < k) {
                    output.put(-1)
                    added++
                }
            }

            output.flip()
            return output
        }

        /**
         * Extract vertex positions from the mesh as a List<Vec3>.
         *
         * Returns all vertex positions in the order they appear in the mesh.
         */
        fun extractPositions(): List<Vec3> {
            val positions = mutableListOf<Vec3>()
            val vertexFloats = buffers.vertexData.asFloatBuffer()
            val strideFloats = 10  // 10 floats per vertex: 3 pos + 3 normal + 4 color

            for (i in 0 until buffers.vertexCount) {
                val base = i * strideFloats
                positions.add(Vec3(
                    vertexFloats.get(base),
                    vertexFloats.get(base + 1),
                    vertexFloats.get(base + 2)
                ))
            }

            return positions
        }
    }

    // ------------------------------------------------------------------------
    // PLY format specification
    // ------------------------------------------------------------------------

    private enum class PlyFormat {
        ASCII, BINARY_LITTLE_ENDIAN, BINARY_BIG_ENDIAN
    }

    private data class PlyProperty(
        val name: String,
        val type: String,
        val isList: Boolean = false,
        val listCountType: String? = null,
        val listElementType: String? = null
    )

    private data class PlyElement(
        val name: String,
        val count: Int,
        val properties: List<PlyProperty>
    )

    private data class PlyHeader(
        val format: PlyFormat,
        val elements: List<PlyElement>
    )

    // ------------------------------------------------------------------------
    // PLY loading
    // ------------------------------------------------------------------------

    fun loadPly(path: String): PlyModel {
        val file = File(path)
        if (!file.exists()) {
            error("PLY file not found: $path")
        }

        lprintln("info,ply", "Loading PLY file: $path")

        // Parse header and data using try-with-resources
        val buffers = BufferedReader(FileReader(file)).use { reader ->
            val header = parseHeader(reader)

            // Find vertex and face elements
            val vertexElement = header.elements.find { it.name == "vertex" }
                ?: error("PLY file must contain a 'vertex' element")
            val faceElement = header.elements.find { it.name == "face" }

            lprintln("info,ply", "Found ${vertexElement.count} vertices" +
                    (faceElement?.let { ", ${it.count} faces" } ?: ", no faces (point cloud)"))

            when (header.format) {
                PlyFormat.ASCII -> parseAsciiData(reader, header, vertexElement, faceElement)
                PlyFormat.BINARY_LITTLE_ENDIAN -> parseBinaryData(file, header, vertexElement, faceElement, ByteOrder.LITTLE_ENDIAN)
                PlyFormat.BINARY_BIG_ENDIAN -> parseBinaryData(file, header, vertexElement, faceElement, ByteOrder.BIG_ENDIAN)
            }
        }

        lprintln("info,ply", "Successfully loaded PLY file")

        return PlyModel(buffers, file.name)
    }

    private fun parseHeader(reader: BufferedReader): PlyHeader {
        val line = reader.readLine()?.trim() ?: error("Empty PLY file")
        if (line != "ply") {
            error("File does not start with 'ply' magic number")
        }

        var format: PlyFormat? = null
        val elements = mutableListOf<PlyElement>()
        var currentElement: PlyElement? = null
        val currentProperties = mutableListOf<PlyProperty>()

        while (true) {
            val headerLine = reader.readLine()?.trim() ?: error("Unexpected end of file in header")

            if (headerLine.isEmpty() || headerLine.startsWith("comment")) {
                continue
            }

            val parts = headerLine.split(Regex("\\s+"))
            when (parts[0]) {
                "format" -> {
                    format = when (parts[1]) {
                        "ascii" -> PlyFormat.ASCII
                        "binary_little_endian" -> PlyFormat.BINARY_LITTLE_ENDIAN
                        "binary_big_endian" -> PlyFormat.BINARY_BIG_ENDIAN
                        else -> error("Unknown PLY format: ${parts[1]}")
                    }
                    lprintln("info,ply", "Format: ${parts[1]}")
                }
                "element" -> {
                    // Save previous element if exists
                    if (currentElement != null) {
                        elements.add(currentElement.copy(properties = currentProperties.toList()))
                        currentProperties.clear()
                    }
                    currentElement = PlyElement(parts[1], parts[2].toInt(), emptyList())
                    lprintln("info,ply", "Element: ${parts[1]} (${parts[2]})")
                }
                "property" -> {
                    if (parts[1] == "list") {
                        currentProperties.add(
                            PlyProperty(
                                name = parts[4],
                                type = "",
                                isList = true,
                                listCountType = parts[2],
                                listElementType = parts[3]
                            )
                        )
                    } else {
                        currentProperties.add(PlyProperty(parts[2], parts[1]))
                    }
                }
                "end_header" -> {
                    if (currentElement != null) {
                        elements.add(currentElement.copy(properties = currentProperties.toList()))
                    }
                    break
                }
            }
        }

        return PlyHeader(format ?: error("No format specified in PLY file"), elements)
    }

    private fun parseAsciiData(
        reader: BufferedReader,
        header: PlyHeader,
        vertexElement: PlyElement,
        faceElement: PlyElement?
    ): PlyMeshBuffers {
        // Parse vertices
        val vertices = mutableListOf<Vertex>()

        val hasX = vertexElement.properties.any { it.name == "x" }
        val hasY = vertexElement.properties.any { it.name == "y" }
        val hasZ = vertexElement.properties.any { it.name == "z" }
        val hasNX = vertexElement.properties.any { it.name == "nx" }
        val hasNY = vertexElement.properties.any { it.name == "ny" }
        val hasNZ = vertexElement.properties.any { it.name == "nz" }
        val colorRedName = findFirstPropertyName(vertexElement, listOf("red", "diffuse_red"))
        val colorGreenName = findFirstPropertyName(vertexElement, listOf("green", "diffuse_green"))
        val colorBlueName = findFirstPropertyName(vertexElement, listOf("blue", "diffuse_blue"))
        val colorAlphaName = findFirstPropertyName(vertexElement, listOf("alpha", "diffuse_alpha"))

        if (!hasX || !hasY || !hasZ) {
            error("PLY vertex element must have x, y, z properties")
        }

        val hasColors = colorRedName != null && colorGreenName != null && colorBlueName != null
        val hasNormals = hasNX && hasNY && hasNZ

        // Build property index map
        val propIndices = vertexElement.properties.mapIndexed { i, prop -> prop.name to i }.toMap()

        repeat(vertexElement.count) {
            val line = reader.readLine()?.trim() ?: error("Unexpected end of file in vertex data")
            val values = line.split(Regex("\\s+"))

            val x = values[propIndices["x"]!!].toFloat()
            val y = values[propIndices["y"]!!].toFloat()
            val z = values[propIndices["z"]!!].toFloat()

            val nx = if (hasNormals) values[propIndices["nx"]!!].toFloat() else 0f
            val ny = if (hasNormals) values[propIndices["ny"]!!].toFloat() else 0f
            val nz = if (hasNormals) values[propIndices["nz"]!!].toFloat() else 1f

            val r: Float
            val g: Float
            val b: Float
            val a: Float

            if (hasColors) {
                // Colors might be 0-255 or 0.0-1.0
                val redVal = values[propIndices[colorRedName]!!].toFloat()
                r = if (redVal > 1.0f) redVal / 255.0f else redVal
                g = if (values[propIndices[colorGreenName]!!].toFloat() > 1.0f)
                    values[propIndices[colorGreenName]!!].toFloat() / 255.0f
                else
                    values[propIndices[colorGreenName]!!].toFloat()
                b = if (values[propIndices[colorBlueName]!!].toFloat() > 1.0f)
                    values[propIndices[colorBlueName]!!].toFloat() / 255.0f
                else
                    values[propIndices[colorBlueName]!!].toFloat()
                a = if (colorAlphaName != null) {
                    val alphaVal = values[propIndices[colorAlphaName]!!].toFloat()
                    if (alphaVal > 1.0f) alphaVal / 255.0f else alphaVal
                } else 1f
            } else {
                r = 1f
                g = 1f
                b = 1f
                a = 1f
            }

            vertices.add(Vertex(Vector3f(x, y, z), Vector3f(nx, ny, nz), Vector4f(r, g, b, a)))
        }

        // Parse faces if present
        val indices = mutableListOf<Int>()
        if (faceElement != null) {
            repeat(faceElement.count) {
                val line = reader.readLine()?.trim() ?: error("Unexpected end of file in face data")
                val values = line.split(Regex("\\s+")).map { it.toInt() }

                val numIndices = values[0]

                // Validate face has enough values
                if (values.size < numIndices + 1) {
                    error("Face has $numIndices indices but only ${values.size - 1} values provided")
                }

                // Validate all indices are within bounds
                for (i in 1..numIndices) {
                    val idx = values[i]
                    if (idx < 0 || idx >= vertices.size) {
                        error("Face index $idx out of bounds (vertex count: ${vertices.size})")
                    }
                }

                if (numIndices == 3) {
                    // Triangle
                    indices.add(values[1])
                    indices.add(values[2])
                    indices.add(values[3])
                } else if (numIndices > 3) {
                    // Polygon - triangulate as fan
                    for (i in 1 until numIndices - 1) {
                        indices.add(values[1])
                        indices.add(values[i + 1])
                        indices.add(values[i + 2])
                    }
                }
            }
        }

        return buildMeshBuffers(vertices, indices, hasColors, hasNormals)
    }

    private fun parseBinaryData(
        file: File,
        header: PlyHeader,
        vertexElement: PlyElement,
        faceElement: PlyElement?,
        byteOrder: ByteOrder
    ): PlyMeshBuffers {
        val hasX = vertexElement.properties.any { it.name == "x" }
        val hasY = vertexElement.properties.any { it.name == "y" }
        val hasZ = vertexElement.properties.any { it.name == "z" }
        val hasNX = vertexElement.properties.any { it.name == "nx" }
        val hasNY = vertexElement.properties.any { it.name == "ny" }
        val hasNZ = vertexElement.properties.any { it.name == "nz" }
        val colorRedName = findFirstPropertyName(vertexElement, listOf("red", "diffuse_red"))
        val colorGreenName = findFirstPropertyName(vertexElement, listOf("green", "diffuse_green"))
        val colorBlueName = findFirstPropertyName(vertexElement, listOf("blue", "diffuse_blue"))
        val colorAlphaName = findFirstPropertyName(vertexElement, listOf("alpha", "diffuse_alpha"))

        if (!hasX || !hasY || !hasZ) {
            error("PLY vertex element must have x, y, z properties")
        }

        val hasColors = colorRedName != null && colorGreenName != null && colorBlueName != null
        val hasNormals = hasNX && hasNY && hasNZ

        // Calculate header size by finding "end_header" in the file
        val fileBytes = file.readBytes()
        var headerSize = 0
        val endHeaderBytes = "end_header".toByteArray()

        // Search for "end_header" in the file
        outer@ for (i in 0 until fileBytes.size - endHeaderBytes.size) {
            var match = true
            for (j in endHeaderBytes.indices) {
                if (fileBytes[i + j] != endHeaderBytes[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                // Find the end of this line (skip to next newline)
                var pos = i + endHeaderBytes.size
                while (pos < fileBytes.size && fileBytes[pos] != '\n'.code.toByte()) {
                    pos++
                }
                if (pos < fileBytes.size) pos++ // Skip the newline itself
                headerSize = pos
                break@outer
            }
        }

        if (headerSize == 0) {
            error("Could not find end_header in binary PLY file")
        }

        // Read binary data
        val buffer = ByteBuffer.wrap(fileBytes, headerSize, fileBytes.size - headerSize)
        buffer.order(byteOrder)

        val vertices = mutableListOf<Vertex>()

        repeat(vertexElement.count) {
            val startPos = buffer.position()

            var x = 0f
            var y = 0f
            var z = 0f
            var nx = 0f
            var ny = 0f
            var nz = 1f
            var r = 1f
            var g = 1f
            var b = 1f
            var a = 1f

            for (prop in vertexElement.properties) {
                if (prop.isList) {
                    // Skip list properties for now (not typical in vertex elements)
                    val count = readValue(buffer, prop.listCountType!!).toInt()
                    repeat(count) {
                        readValue(buffer, prop.listElementType!!)
                    }
                } else {
                    val value = readValue(buffer, prop.type)
                    when (prop.name) {
                        "x" -> x = value
                        "y" -> y = value
                        "z" -> z = value
                        "nx" -> nx = value
                        "ny" -> ny = value
                        "nz" -> nz = value
                        colorRedName -> r = if (prop.type == "uchar") value / 255f else value
                        colorGreenName -> g = if (prop.type == "uchar") value / 255f else value
                        colorBlueName -> b = if (prop.type == "uchar") value / 255f else value
                        colorAlphaName -> a = if (prop.type == "uchar") value / 255f else value
                    }
                }
            }

            vertices.add(Vertex(Vector3f(x, y, z), Vector3f(nx, ny, nz), Vector4f(r, g, b, a)))
        }

        // Parse faces if present
        val indices = mutableListOf<Int>()
        if (faceElement != null) {
            repeat(faceElement.count) {
                val numIndices = readValue(buffer, faceElement.properties[0].listCountType!!).toInt()
                val faceIndices = mutableListOf<Int>()
                repeat(numIndices) {
                    faceIndices.add(readValue(buffer, faceElement.properties[0].listElementType!!).toInt())
                }

                // Validate all indices are within bounds
                for (idx in faceIndices) {
                    if (idx < 0 || idx >= vertices.size) {
                        error("Face index $idx out of bounds (vertex count: ${vertices.size})")
                    }
                }

                if (numIndices == 3) {
                    indices.addAll(faceIndices)
                } else if (numIndices > 3) {
                    // Triangulate as fan
                    for (i in 1 until numIndices - 1) {
                        indices.add(faceIndices[0])
                        indices.add(faceIndices[i])
                        indices.add(faceIndices[i + 1])
                    }
                }
            }
        }

        return buildMeshBuffers(vertices, indices, hasColors, hasNormals)
    }

    private fun readValue(buffer: ByteBuffer, type: String): Float {
        return when (type) {
            "char", "int8" -> buffer.get().toFloat()
            "uchar", "uint8" -> buffer.get().toInt().and(0xFF).toFloat()
            "short", "int16" -> buffer.short.toFloat()
            "ushort", "uint16" -> buffer.short.toInt().and(0xFFFF).toFloat()
            "int", "int32" -> buffer.int.toFloat()
            "uint", "uint32" -> buffer.int.toLong().and(0xFFFFFFFFL).toFloat()
            "float", "float32" -> buffer.float
            "double", "float64" -> buffer.double.toFloat()
            else -> error("Unsupported property type: $type")
        }
    }

    private fun findFirstPropertyName(element: PlyElement, candidates: List<String>): String? {
        val names = element.properties.map { it.name }.toSet()
        return candidates.firstOrNull { it in names }
    }

    private data class Vertex(
        val position: Vector3f,
        val normal: Vector3f,
        val color: Vector4f
    )

    private fun buildMeshBuffers(
        vertices: List<Vertex>,
        indices: List<Int>,
        hasColors: Boolean,
        hasNormals: Boolean
    ): PlyMeshBuffers {
        val vertexCount = vertices.size
        val indexCount = indices.size

        // Layout: [pos.xyz][normal.xyz][color.rgba] = 10 floats per vertex
        val componentsPerVertex = 10
        val strideBytes = componentsPerVertex * 4

        // Note: These buffers are allocated with memAlloc and should be freed by the caller
        // when they're done with the PlyMeshBuffers. The buffers are typically transferred
        // to GPU and can be freed after transfer.
        val vertexData = memAlloc(vertexCount * strideBytes)

        vertices.forEach { vertex ->
            // Position
            vertexData.putFloat(vertex.position.x)
            vertexData.putFloat(vertex.position.y)
            vertexData.putFloat(vertex.position.z)

            // Normal
            vertexData.putFloat(vertex.normal.x)
            vertexData.putFloat(vertex.normal.y)
            vertexData.putFloat(vertex.normal.z)

            // Color
            vertexData.putFloat(vertex.color.x)
            vertexData.putFloat(vertex.color.y)
            vertexData.putFloat(vertex.color.z)
            vertexData.putFloat(vertex.color.w)
        }
        vertexData.flip()

        val indexData = if (indices.isNotEmpty()) {
            val buffer = memAllocInt(indexCount)
            indices.forEach { buffer.put(it) }
            buffer.flip()
            buffer
        } else null

        return PlyMeshBuffers(
            vertexData = vertexData,
            indexData = indexData,
            vertexCount = vertexCount,
            indexCount = indexCount,
            hasColors = hasColors,
            hasNormals = hasNormals,
            isPointCloud = indices.isEmpty()
        )
    }

    // ------------------------------------------------------------------------
    // Default shaders (GLSL) for SSBO vertex pulling + push constants
    // ------------------------------------------------------------------------

    val plyVertexShader = """
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require

layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats (10 for PLY)
    uint useVertexColor;   // bool as uint

    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

layout(location = 0) out vec3 vViewPos;
layout(location = 1) out vec3 vNormalVS;
layout(location = 2) out vec4 vColor;

void main()
{
    uint idx = pc.indexBuffer.indexData[pc.indexOffset+uint(gl_VertexIndex)];
    uint base = pc.vertexOffset + idx * pc.vertexStride;

    vec3 pos = vec3(
        pc.vertexBuffer.vertexData[base + 0],
        pc.vertexBuffer.vertexData[base + 1],
        pc.vertexBuffer.vertexData[base + 2]
    );

    vec3 normal = vec3(
        pc.vertexBuffer.vertexData[base + 3],
        pc.vertexBuffer.vertexData[base + 4],
        pc.vertexBuffer.vertexData[base + 5]
    );

    vec4 color = vec4(
        pc.vertexBuffer.vertexData[base + 6],
        pc.vertexBuffer.vertexData[base + 7],
        pc.vertexBuffer.vertexData[base + 8],
        pc.vertexBuffer.vertexData[base + 9]
    );

    if (pc.useVertexColor == 0u) {
        color = vec4(1.0);
    }

    vec4 viewPos = pc.proj * pc.modelView * vec4(pos, 1.0);

    mat3 mv3 = mat3(pc.modelView);
    vec3 normalVS = normalize(mv3 * normal);

    vViewPos = viewPos.xyz;
    vNormalVS = normalVS;
    vColor = color * pc.materialBaseColor;

    gl_Position = viewPos;
}
""".trimIndent()

    val plyFragmentShader = """
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 2) in vec4 vColor;
layout(location = 1) in vec3 vNormalVS;

void main()
{
    // Simple directional lighting from upper-right-front
    float d = abs(dot(vNormalVS.xyz, normalize(vec3(1, 0.4, -0.2))));
    uFragColor = vColor * vec4(vNormalVS.rgb * d, 1);
}
""".trimIndent()

    // ------------------------------------------------------------------------
    // Point cloud visualization shaders
    // Screen-aligned quads with crisp circles for point rendering
    // Uses vertex pulling to generate 6 vertices per point (2 triangles forming a quad)
    // ------------------------------------------------------------------------

    val plyPointCloudVertexShader = """
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require

layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats (10 for PLY)
    uint useVertexColor;   // bool as uint
    float pointSize;       // point size in pixels
    vec2 viewportSize;     // viewport dimensions (width, height)

    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vQuadCoord;

void main()
{
    // Each point generates 6 vertices (2 triangles)
    // gl_VertexIndex / 6 = point index
    // gl_VertexIndex % 6 = corner index (0-5)
    uint pointIndex = uint(gl_VertexIndex) / 6u;
    uint cornerIndex = uint(gl_VertexIndex) % 6u;
    
    // Get the actual point data
    uint idx = pc.indexBuffer.indexData[pc.indexOffset + pointIndex];
    uint base = pc.vertexOffset + idx * pc.vertexStride;

    vec3 pos = vec3(
        pc.vertexBuffer.vertexData[base + 0],
        pc.vertexBuffer.vertexData[base + 1],
        pc.vertexBuffer.vertexData[base + 2]
    );

    vec4 color = vec4(
        pc.vertexBuffer.vertexData[base + 6],
        pc.vertexBuffer.vertexData[base + 7],
        pc.vertexBuffer.vertexData[base + 8],
        pc.vertexBuffer.vertexData[base + 9]
    );

    if (pc.useVertexColor == 0u) {
        color = vec4(1.0);
    }

    // Transform point to clip space
    vec4 clipPos = pc.proj * pc.modelView * vec4(pos, 1.0);
    
    // Calculate quad size in clip space (convert from pixels)
    vec2 quadSize = pc.pointSize / pc.viewportSize;
    
    // Generate quad corners
    // Two triangles: (0,1,2) and (2,3,0) mapped to corners
    vec2 corners[6];
    corners[0] = vec2(-1.0, -1.0);  // bottom-left
    corners[1] = vec2( 1.0, -1.0);  // bottom-right
    corners[2] = vec2( 1.0,  1.0);  // top-right
    corners[3] = vec2( 1.0,  1.0);  // top-right
    corners[4] = vec2(-1.0,  1.0);  // top-left
    corners[5] = vec2(-1.0, -1.0);  // bottom-left
    
    vec2 corner = corners[cornerIndex];
    
    // Offset in clip space
    vec2 offset = corner * quadSize;
    
    // Apply offset to clip position
    gl_Position = clipPos + vec4(offset, 0.0, 0.0);
    
    // Pass through color and quad coordinates for fragment shader
    vColor = color * pc.materialBaseColor;
    vQuadCoord = corner; // -1 to 1 range for circle calculation
}
""".trimIndent()

    val plyPointCloudFragmentShader = """
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vQuadCoord;

void main()
{
    // Calculate distance from center for crisp circles
    float dist = length(vQuadCoord);
    
    // Discard fragments outside circle radius
    if (dist > 1.0) {
        discard;
    }
    
    // Smooth anti-aliasing at edge
    float alpha = 1.0 - smoothstep(0.9, 1.0, dist);
    
    uFragColor = vec4(vColor.rgb, vColor.a * alpha);
}
""".trimIndent()
}
