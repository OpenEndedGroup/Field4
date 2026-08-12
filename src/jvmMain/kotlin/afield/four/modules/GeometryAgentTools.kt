package afield.four.modules

import afield.ai.core.AgentToolExtensions
import afield.ai.core.CarriesToolImages
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolImage
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.bounds
import afield.stdlib.eulerCharacteristic
import afield.stdlib.isWatertight
import afield.stdlib.renderPng
import afield.stdlib.surfaceArea
import afield.stdlib.topology
import afield.stdlib.volume
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A worked, public example of giving Field's AI its own tools — two of them, over the geometry the
 * loaders in [MeshFromDisk.kt][meshFromAnyFile] read off disk.
 *
 * # What this is for
 *
 * `afield.ai.core.AgentToolExtensions` is how any code outside `afield.ai.core` adds tools to AI4. It
 * is careful, well-documented, and until 2026-08-11 the only thing in the tree that used it was a
 * gitignored personal package — so there was nothing a reader could run, and nothing a build could
 * check. This is the runnable half of `public/docs/aiFeatures.md`'s "Give the AI a tool of your own",
 * and it is deliberately built out of parts that are already interesting on their own:
 * [meshFromAnyFile] reads OBJ, STL, PLY, glTF/GLB and everything else Assimp handles, and
 * [afield.stdlib.renderPng] rasterises the result with no GPU, no window and no AWT.
 *
 * That last property is what makes the second tool worth copying. A model asked "is the scan I dropped
 * in there the right way up?" can *look*, because [InspectGeometryFileResult] implements
 * [CarriesToolImages] and the picture travels as an image rather than as base64 spent in a JSON string.
 *
 * # Using it
 *
 * ```kotlin
 * // in a box:
 * GeometryAgentTools.addSearchRoot(File(System.getProperty("user.home"), "scans"))
 * GeometryAgentTools.register()
 * // then open AI Chat and ask about the files in there.
 * ```
 *
 * [register] is idempotent, so leaving those two lines in a box you re-run is fine, and — since
 * 2026-08-11 — a re-run reaches a conversation that is already open.
 *
 * # The three things this example is trying to demonstrate
 *
 *  1. **A gate that really gates.** Both tools are unavailable until a search root is added. A tool set
 *     that is always on costs its schema on every request in every conversation, including the ones
 *     that have nothing to do with it.
 *  2. **A result that summarises itself.** Both results implement [SummarisesForToolLog], so the tool
 *     I/O log — which `AiToolUseImprovementMonitor` reads — records what happened rather than a class
 *     name.
 *  3. **Refusing to read outside the roots.** These tools take a *path from a language model*. The
 *     roots are the entire permission model, and [resolveInsideRoots] is where it is enforced; a
 *     symlink or a `../` that escapes is an error, not a read. Every tool that takes a path from a
 *     model needs an equivalent, and it is the part an example is most likely to leave out.
 *
 * # What this does not do
 *
 * It does not modify anything. There is deliberately no `ConvertGeometryFile` here: a writing tool
 * needs a confirmation protocol (the personal house cluster's `commit=false` preview pattern), which is
 * a bigger subject than one example should try to carry, and getting it half-right in a page people
 * copy from would be worse than leaving it out.
 */
object GeometryAgentTools {

    /**
     * Extensions [meshFromAnyFile] can open, lower-cased and without the dot.
     *
     * Kept as data rather than derived from the loaders, because the loaders' own dispatch has an
     * `else -> assimpToTriMesh(path)` branch that will *try* anything. Listing what we mean to offer is
     * more honest to a model than offering everything and failing on `.txt`.
     */
    val LOADABLE_EXTENSIONS: Set<String> =
        setOf("obj", "stl", "ply", "glb", "gltf", "fbx", "dae", "3ds", "blend", "off")

    private val roots = CopyOnWriteArrayList<File>()

    /**
     * Let the AI see geometry files under [dir]. Nothing outside a root added here is readable.
     *
     * Stored canonically so that the containment check in [resolveInsideRoots] compares like with like:
     * a root reached through a symlink and a path reached through the same symlink must agree.
     */
    fun addSearchRoot(dir: File) {
        val canonical = dir.canonicalFile
        require(canonical.isDirectory) { "not a directory: $canonical" }
        if (roots.none { it == canonical }) roots.add(canonical)
    }

    /** Take every root away, which also closes the gate. Symmetric with [addSearchRoot], and for tests. */
    fun clearSearchRoots() = roots.clear()

    /** The roots, canonicalised, in the order they were added. */
    fun searchRoots(): List<File> = roots.toList()

    /**
     * The gate both tools share: is there anywhere to look?
     *
     * Asked on every turn, which is why it is a field check and not a directory walk.
     */
    fun enabled(): Boolean = roots.isNotEmpty()

    /**
     * Register both tools with AI4.
     *
     * Idempotent. Safe to call from a box you re-run, and safe to call before or after opening a chat.
     */
    fun register() {
        val gate: (ToolExecutionContext) -> Boolean = { enabled() }

        AgentToolExtensions.register(ListGeometryFiles::class.java, gate) { _, request ->
            listFiles(request)
        }
        AgentToolExtensions.register(InspectGeometryFile::class.java, gate) { _, request ->
            inspect(request)
        }
    }

    /** Retire both tools. A conversation that is open loses them on its next turn. */
    fun unregister() {
        AgentToolExtensions.unregister(ListGeometryFiles::class.java)
        AgentToolExtensions.unregister(InspectGeometryFile::class.java)
    }

    // ---------------------------------------------------------------- path safety

    /**
     * Resolve a model-supplied [path] to a real file inside one of the [searchRoots], or throw.
     *
     * Accepts either an absolute path or one relative to a root, because a model will produce both —
     * it sees root-relative paths from [ListGeometryFiles] and absolute ones from whatever the human
     * pasted into the conversation.
     *
     * The check is on the **canonical** path, after symlinks, so `roots/link-to-etc/passwd` and
     * `roots/../../etc/passwd` are both refused. `startsWith` on the canonical *path string* would
     * accept `/data/scans-private` for a root of `/data/scans`, so the walk is up the parent chain.
     */
    private fun resolveInsideRoots(path: String): File {
        require(path.isNotBlank()) { "path is empty" }
        check(roots.isNotEmpty()) { "no geometry search roots are configured" }

        val candidates = buildList {
            add(File(path))
            roots.forEach { add(File(it, path)) }
        }
        val existing = candidates.firstOrNull { it.isFile }
            ?: throw IllegalArgumentException(
                "no such file: '$path'. Paths are absolute or relative to a search root " +
                    "(${roots.joinToString { it.path }}); call ListGeometryFiles to see what is there."
            )

        val canonical = existing.canonicalFile
        val inside = roots.any { root ->
            generateSequence(canonical) { it.parentFile }.any { it == root }
        }
        if (!inside) {
            throw IllegalArgumentException(
                "'$path' resolves to $canonical, which is outside every geometry search root " +
                    "(${roots.joinToString { it.path }}). Refusing to read it."
            )
        }
        return canonical
    }

    // ---------------------------------------------------------------- the tools

    private fun listFiles(request: ListGeometryFiles): ListGeometryFilesResult {
        val needle = request.nameContains.trim().lowercase()
        val limit = request.limit.coerceIn(1, 500)
        val found = ArrayList<GeometryFileEntry>()
        var total = 0

        for (root in roots) {
            root.walkTopDown()
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.lowercase() in LOADABLE_EXTENSIONS }
                .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
                .forEach { file ->
                    total++
                    if (found.size < limit) {
                        found += GeometryFileEntry(
                            path = file.relativeToOrSelf(root).path,
                            root = root.path,
                            format = file.extension.lowercase(),
                            bytes = file.length(),
                        )
                    }
                }
        }
        found.sortBy { it.path }
        return ListGeometryFilesResult(
            files = found,
            totalMatches = total,
            truncated = total > found.size,
            searchRoots = roots.map { it.path },
        )
    }

    private fun inspect(request: InspectGeometryFile): InspectGeometryFileResult {
        val file = resolveInsideRoots(request.path)
        val owningRoot = roots.first { root ->
            generateSequence(file) { it.parentFile }.any { it == root }
        }
        val mesh: TriMesh = meshFromAnyFile(file.path)
        val bounds = mesh.bounds()
        val size = bounds.size
        val topology = mesh.topology()

        val images = if (request.render && !mesh.isEmpty) {
            val png = mesh.renderPng(
                MeshView(
                    yawDegrees = request.yawDegrees,
                    pitchDegrees = request.pitchDegrees,
                    width = 640,
                    height = 480,
                    wireframe = request.wireframe,
                ),
                caption = "",
                title = null,
            )
            listOf(
                ToolImage(
                    dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
                    caption = "${file.name} — ${mesh.triangleCount} triangles, viewed from " +
                        "${request.yawDegrees.toInt()}° yaw / ${request.pitchDegrees.toInt()}° above. " +
                        "Two-light lambert over the mesh's own colours; the units are the file's.",
                )
            )
        } else {
            emptyList()
        }

        return InspectGeometryFileResult(
            // Root-relative, matching what ListGeometryFiles hands out, so a model can round-trip a
            // path between the two tools. It also keeps the human's home directory out of the
            // conversation, which an absolute canonical path would put there on every single call.
            path = file.relativeToOrSelf(owningRoot).path,
            root = owningRoot.path,
            format = file.extension.lowercase(),
            bytesOnDisk = file.length(),
            vertexCount = mesh.vertexCount,
            triangleCount = mesh.triangleCount,
            hasNormals = mesh.hasNormals,
            hasUvs = mesh.hasUvs,
            hasColors = mesh.hasColors,
            extent = listOf(size.x.toDouble(), size.y.toDouble(), size.z.toDouble()),
            minCorner = listOf(bounds.min.x.toDouble(), bounds.min.y.toDouble(), bounds.min.z.toDouble()),
            surfaceArea = mesh.surfaceArea(),
            volume = if (mesh.isWatertight()) mesh.volume() else null,
            watertight = mesh.isWatertight(),
            eulerCharacteristic = mesh.eulerCharacteristic(),
            boundaryLoopCount = topology.boundaryLoops.size,
            notes = buildNotes(mesh, file),
            toolImages = images,
        )
    }

    /**
     * The sentences a model would otherwise have to infer, and would often infer wrongly.
     *
     * "Not watertight" reads as a defect and usually is not one: an artist's model is a shell, and every
     * UV seam is a real cut in the surface. Saying so here is cheaper than the three turns it takes to
     * talk a model out of calling `repaired()` on a perfectly good car.
     */
    private fun buildNotes(mesh: TriMesh, file: File): List<String> = buildList {
        if (mesh.isEmpty) {
            add("This file loaded with no triangles at all — it may be a point cloud, or an empty scene.")
            return@buildList
        }
        if (!mesh.isWatertight()) {
            add(
                "Not watertight. For a modelled asset this is normal — UV seams and hard edges are real " +
                    "cuts in the surface — and only matters if you need a volume, a boolean or a printer."
            )
        }
        if (!mesh.hasNormals) {
            add("No vertex normals in the file; the preview crease-shades them at 35°.")
        }
        if (file.extension.equals("stl", ignoreCase = true)) {
            add(
                "STL is triangle soup: every triangle carries its own three vertices, so the vertex " +
                    "count is 3x the triangle count and the Euler characteristic is meaningless until " +
                    "the mesh is welded."
            )
        }
    }
}

// ==============================================================================================
// The request classes — which are the schema, and the only thing the model is shown
// ==============================================================================================

/**
 * Tool 1: what is there.
 *
 * `@JsonClassDescription` is the tool description and `@JsonPropertyDescription` the per-argument one;
 * `ToolSchema` turns both into the JSON schema in the request, so this annotation *is* the prompt for
 * this tool. Write it for a reader who cannot see the code.
 */
@JsonClassDescription(
    "List the 3D geometry files (OBJ, STL, PLY, glTF/GLB, FBX, DAE) available to inspect. " +
        "Call this before InspectGeometryFile to find out what paths exist."
)
data class ListGeometryFiles(
    @JsonPropertyDescription("Only list files whose name contains this text, case-insensitively. Omit for all of them.")
    val nameContains: String = "",
    @JsonPropertyDescription("At most this many files, 1..500. Defaults to 40.")
    val limit: Int = 40,
)

data class GeometryFileEntry(
    val path: String,
    val root: String,
    val format: String,
    val bytes: Long,
)

data class ListGeometryFilesResult(
    val files: List<GeometryFileEntry>,
    val totalMatches: Int,
    val truncated: Boolean,
    val searchRoots: List<String>,
) : SummarisesForToolLog {
    override fun toolLogSummary(): String =
        "files=${files.size}/$totalMatches truncated=$truncated roots=${searchRoots.size}"
}

/**
 * Tool 2: what one of them *is*, and what it looks like.
 *
 * The camera arguments are here on purpose. A model that can only get one fixed view has to guess about
 * everything the view hides; a model that can ask for another yaw can check its own answer, which is
 * the difference between a tool that reports and a tool a model can investigate with.
 */
@JsonClassDescription(
    "Load one 3D geometry file and report its measurements — vertex and triangle counts, bounding-box " +
        "extent, surface area, watertightness — and, unless render is false, return a rendered image " +
        "of it to look at. Paths come from ListGeometryFiles."
)
data class InspectGeometryFile(
    @JsonPropertyDescription("The file to inspect: a path from ListGeometryFiles, or an absolute path inside a search root.")
    val path: String,
    @JsonPropertyDescription("Return a rendered image of the mesh. True by default; set false when only the numbers are wanted.")
    val render: Boolean = true,
    @JsonPropertyDescription("Camera orbit around the vertical axis in degrees. Ask again with a different value to see another side.")
    val yawDegrees: Double = 28.0,
    @JsonPropertyDescription("Camera height above the horizon in degrees. Positive looks down on the object.")
    val pitchDegrees: Double = 18.0,
    @JsonPropertyDescription("Draw the triangle edges over the shading. Useful for judging mesh density; illegible above about 50,000 triangles.")
    val wireframe: Boolean = false,
)

/**
 * What a model gets back — and, separately, what it gets to see.
 *
 * [toolImages] comes from [CarriesToolImages], whose `@get:JsonIgnore` keeps the base64 PNG out of the
 * function-call output. That is not an optimisation: a model cannot see an image encoded inside a JSON
 * string, so a picture sent that way is pure cost. The bridge lifts these onto the conversation as a
 * real image message instead.
 */
data class InspectGeometryFileResult(
    /** Relative to [root], which is the form [ListGeometryFiles] reports and accepts back. */
    val path: String,
    val root: String,
    val format: String,
    val bytesOnDisk: Long,
    val vertexCount: Int,
    val triangleCount: Int,
    val hasNormals: Boolean,
    val hasUvs: Boolean,
    val hasColors: Boolean,
    val extent: List<Double>,
    val minCorner: List<Double>,
    val surfaceArea: Double,
    /** Null unless the mesh is watertight — a volume for an open shell is a number with no meaning. */
    val volume: Double?,
    val watertight: Boolean,
    val eulerCharacteristic: Int,
    val boundaryLoopCount: Int,
    val notes: List<String>,
    override val toolImages: List<ToolImage> = emptyList(),
) : SummarisesForToolLog, CarriesToolImages {

    override fun toolLogSummary(): String =
        "${File(path).name} verts=$vertexCount tris=$triangleCount watertight=$watertight " +
            "extent=%.3f/%.3f/%.3f images=%d".format(
                extent.getOrElse(0) { 0.0 }, extent.getOrElse(1) { 0.0 }, extent.getOrElse(2) { 0.0 },
                toolImages.size,
            )
}
