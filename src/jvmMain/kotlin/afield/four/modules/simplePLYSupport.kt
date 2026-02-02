@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

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
import afield.stdlib._moduleHere
import afield.stdlib.macro_moduleHereWithDefaultShaders
import kotlinx.coroutines.DelicateCoroutinesApi
import org.joml.Vector3f
import org.joml.Vector4f
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
        primitiveType = points
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
            m.inside.current.liveUniformStructures["VertexData"]!!["modelView"] = camera.m()
            m.inside.current.liveUniformStructures["VertexData"]!!["proj"] = camera.p()
            m.inside.current.liveUniformStructures["VertexData"]!!["vertexOffset"] = 0
            m.inside.current.liveUniformStructures["VertexData"]!!["indexOffset"] = 0
            m.inside.current.liveUniformStructures["VertexData"]!!["vertexStride"] = 10
            m.inside.current.liveUniformStructures["VertexData"]!!["materialBaseColor"] = Vector4f(1f, 1f, 1f, 1f)
            m.inside.current.liveUniformStructures["VertexData"]!!["useVertexColor"] = if (buffers.hasColors) 1 else 0
            
            // Set point size for point cloud shaders (will be ignored if not present in shader)
            if ("pointSize" in m.inside.current.liveUniformStructures["VertexData"]!!.offsets) {
                m.inside.current.liveUniformStructures["VertexData"]!!["pointSize"] = pointSize
            }

            val vertexData = defaultBuffer {
                size = (buffers.vertexCount * 10).floats
            }.buffer

            m.initializePushConstantsFromModule(m.inside)
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
        val hasRed = vertexElement.properties.any { it.name == "red" }
        val hasGreen = vertexElement.properties.any { it.name == "green" }
        val hasBlue = vertexElement.properties.any { it.name == "blue" }
        val hasAlpha = vertexElement.properties.any { it.name == "alpha" }

        if (!hasX || !hasY || !hasZ) {
            error("PLY vertex element must have x, y, z properties")
        }

        val hasColors = hasRed && hasGreen && hasBlue
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
                val redVal = values[propIndices["red"]!!].toFloat()
                r = if (redVal > 1.0f) redVal / 255.0f else redVal
                g = if (values[propIndices["green"]!!].toFloat() > 1.0f) 
                    values[propIndices["green"]!!].toFloat() / 255.0f 
                else 
                    values[propIndices["green"]!!].toFloat()
                b = if (values[propIndices["blue"]!!].toFloat() > 1.0f) 
                    values[propIndices["blue"]!!].toFloat() / 255.0f 
                else 
                    values[propIndices["blue"]!!].toFloat()
                a = if (hasAlpha) {
                    val alphaVal = values[propIndices["alpha"]!!].toFloat()
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
        val hasRed = vertexElement.properties.any { it.name == "red" }
        val hasGreen = vertexElement.properties.any { it.name == "green" }
        val hasBlue = vertexElement.properties.any { it.name == "blue" }
        val hasAlpha = vertexElement.properties.any { it.name == "alpha" }

        if (!hasX || !hasY || !hasZ) {
            error("PLY vertex element must have x, y, z properties")
        }

        val hasColors = hasRed && hasGreen && hasBlue
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
                        "red" -> r = if (prop.type == "uchar") value / 255f else value
                        "green" -> g = if (prop.type == "uchar") value / 255f else value
                        "blue" -> b = if (prop.type == "uchar") value / 255f else value
                        "alpha" -> a = if (prop.type == "uchar") value / 255f else value
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

layout (binding = 0) uniform VertexData {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats (10 for PLY)
    uint useVertexColor;   // bool as uint
} data;

layout(push_constant) uniform PushConstants {
    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

layout(location = 0) out vec3 vViewPos;
layout(location = 1) out vec3 vNormalVS;
layout(location = 2) out vec4 vColor;

void main()
{
    uint idx = pc.indexBuffer.indexData[data.indexOffset+uint(gl_VertexIndex)];
    uint base = data.vertexOffset + idx * data.vertexStride;

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

    if (data.useVertexColor == 0u) {
        color = vec4(1.0);
    }

    vec4 viewPos = data.proj * data.modelView * vec4(pos, 1.0);

    mat3 mv3 = mat3(data.modelView);
    vec3 normalVS = normalize(mv3 * normal);

    vViewPos = viewPos.xyz;
    vNormalVS = normalVS;
    vColor = color * data.materialBaseColor;

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

layout (binding = 0) uniform VertexData {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats (10 for PLY)
    uint useVertexColor;   // bool as uint
    float pointSize;       // point size in pixels
} data;

layout(push_constant) uniform PushConstants {
    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

layout(location = 0) out vec3 vViewPos;
layout(location = 1) out vec4 vColor;
layout(location = 2) out float vPointSize;

void main()
{
    uint idx = pc.indexBuffer.indexData[data.indexOffset+uint(gl_VertexIndex)];
    uint base = data.vertexOffset + idx * data.vertexStride;

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

    if (data.useVertexColor == 0u) {
        color = vec4(1.0);
    }

    vec4 clipPos = data.proj * data.modelView * vec4(pos, 1.0);

    vViewPos = clipPos.xyz;
    vColor = color * data.materialBaseColor;
    vPointSize = data.pointSize;

    gl_Position = clipPos;
    gl_PointSize = data.pointSize;
}
""".trimIndent()

    val plyPointCloudFragmentShader = """
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec4 vColor;
layout(location = 2) in float vPointSize;

void main()
{
    // Calculate distance from center of point sprite
    vec2 coord = gl_PointCoord * 2.0 - 1.0;
    float dist = length(coord);
    
    // Discard fragments outside circle radius for crisp circles
    if (dist > 1.0) {
        discard;
    }
    
    // Optional: smooth anti-aliasing at edge
    float alpha = 1.0 - smoothstep(0.9, 1.0, dist);
    
    uFragColor = vec4(vColor.rgb, vColor.a * alpha);
}
""".trimIndent()

    // Alternative geometry shader-based approach for instanced quads
    // Note: This is a template that requires viewport dimensions to be added
    val plyPointCloudGeometryShader = """
#version 450
layout(points) in;
layout(triangle_strip, max_vertices = 4) out;

layout (binding = 0) uniform VertexData {
    mat4 modelView;
    mat4 proj;
    vec4 materialBaseColor;
    uint vertexOffset;
    uint indexOffset;
    uint vertexStride;
    uint useVertexColor;
    float pointSize;
    vec2 viewportSize;  // Add this to your uniform structure (width, height in pixels)
} data;

layout(location = 0) in vec3 vViewPos[];
layout(location = 1) in vec4 vColor[];
layout(location = 2) in float vPointSize[];

layout(location = 0) out vec4 gColor;
layout(location = 1) out vec2 gTexCoord;

void main()
{
    vec4 center = gl_in[0].gl_Position;
    float size = vPointSize[0];
    
    // Calculate screen-aligned quad size in clip space
    // Convert pixel size to normalized device coordinates
    vec2 screenSize = vec2(size) / data.viewportSize;
    
    gColor = vColor[0];
    
    // Bottom-left
    gl_Position = center + vec4(-screenSize.x, -screenSize.y, 0.0, 0.0);
    gTexCoord = vec2(0.0, 0.0);
    EmitVertex();
    
    // Bottom-right
    gl_Position = center + vec4(screenSize.x, -screenSize.y, 0.0, 0.0);
    gTexCoord = vec2(1.0, 0.0);
    EmitVertex();
    
    // Top-left
    gl_Position = center + vec4(-screenSize.x, screenSize.y, 0.0, 0.0);
    gTexCoord = vec2(0.0, 1.0);
    EmitVertex();
    
    // Top-right
    gl_Position = center + vec4(screenSize.x, screenSize.y, 0.0, 0.0);
    gTexCoord = vec2(1.0, 1.0);
    EmitVertex();
    
    EndPrimitive();
}
""".trimIndent()

    val plyPointCloudGeometryFragmentShader = """
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec4 gColor;
layout(location = 1) in vec2 gTexCoord;

void main()
{
    // Calculate distance from center for crisp circle
    vec2 coord = gTexCoord * 2.0 - 1.0;
    float dist = length(coord);
    
    // Discard fragments outside circle
    if (dist > 1.0) {
        discard;
    }
    
    // Smooth anti-aliasing at edge
    float alpha = 1.0 - smoothstep(0.9, 1.0, dist);
    
    uFragColor = vec4(gColor.rgb, gColor.a * alpha);
}
""".trimIndent()
}
