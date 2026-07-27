@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.Box
import afield.SessionInformation
import afield.listWorkspaceProjects
import afield.projectsDir
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream

/**
 * Path resolution for the running Field session: turns a short spec into a real `File` or `Path`.
 * The [files] / [fileManager] properties (and the [String.file], [String.path],
 * [String.absolutePath] extensions) hand you the [FieldFileManager] for the current box or
 * session, which resolves, in order: absolute and `~/` paths; `%document/…` and `%project/…`;
 * any name registered with [FileManager.add] or `FileManager["name"] = dir`; globs, by walking
 * the search roots and taking the first sorted match; and otherwise a plain relative name looked
 * up in the document's `files/` directory and then in the `files/` directory of every workspace
 * project the document belongs to.
 *
 * Resolutions are cached per session and dropped when the named-directory table changes, and a
 * child path that would escape its root is rejected. Every lookup is appended to the session
 * root's [FILE_MANAGER_ACCESS_HISTORY_PROPERTY] property, trimmed to
 * [FileManager.maxAccessHistoryEntries] — so these calls need an active session or Box context and
 * will error without one.
 */

const val FILE_MANAGER_ACCESS_HISTORY_PROPERTY = "fileManagerAccessHistory"

class FieldFileManager internal constructor(private val session: SessionInformation) {
    private val resolved = ConcurrentHashMap<String, FileResolution>()
    private val sequence = AtomicLong(0)
    private var seenGlobalVersion = FileManager.globalVersion()

    init {
        resetSessionAccessHistory()
    }

    val documentDirectory: Path
        get() = File(session.filename).absoluteFile.toPath().normalize()

    val documentFilesDirectory: Path
        get() = documentDirectory.resolve("files").normalize().also { Files.createDirectories(it) }

    val projectFilesDirectories: List<Path>
        get() = currentProjectFilesDirectories(create = true)

    fun file(spec: String): File = resolveAndRecord(spec, "file").toFile()

    fun path(spec: String): Path = resolveAndRecord(spec, "path")

    fun absolutePath(spec: String): String = resolveAndRecord(spec, "absolutePath").toAbsolutePath().normalize().toString()

    fun clearCache() {
        resolved.clear()
    }

    private fun resolveAndRecord(spec: String, access: String): Path {
        val resolution = resolve(spec)
        recordAccess(spec, access, resolution)
        return resolution.path
    }

    private fun resolve(spec: String): FileResolution {
        refreshGlobalStateIfNeeded()
        val cleanSpec = spec.takeIf { it.isNotBlank() } ?: error("FileManager cannot resolve a blank path")

        resolved[cleanSpec]?.takeIf { Files.exists(it.path) }?.let { return it }
        resolved.remove(cleanSpec)

        val resolution = when {
            isAbsoluteLike(cleanSpec) -> FileResolution(expandHome(cleanSpec).toAbsolutePath().normalize(), "absolute")
            cleanSpec.startsWith("%") -> resolveMappedPath(cleanSpec)
            hasGlob(cleanSpec) -> searchFirst(
                roots = searchRoots(),
                pattern = cleanSpec
            ) ?: error("No file matched '$cleanSpec' in ${searchRoots().joinToString { it.toString() }}")

            else -> resolveRelative(cleanSpec)
        }

        if (Files.exists(resolution.path)) resolved[cleanSpec] = resolution
        return resolution
    }

    private fun resolveMappedPath(spec: String): FileResolution {
        val (name, rest) = splitMappedPath(spec)
        return when (name) {
            "document" -> resolveInRoot(documentFilesDirectory, rest, spec, createRoot = true)
            "project" -> resolveProjectPath(rest, spec)
            else -> {
                val root = FileManager.namedDirectory(name)
                    ?: error("No FileManager directory named '%$name'")
                resolveInRoot(root, rest, spec, createRoot = false, source = "%$name")
            }
        }
    }

    private fun resolveRelative(spec: String): FileResolution {
        val roots = searchRoots()
        for (root in roots) {
            val candidate = resolveChild(root.path, spec)
            if (Files.exists(candidate)) return FileResolution(candidate, root.source)
        }
        return FileResolution(resolveChild(documentFilesDirectory, spec), "document")
    }

    private fun resolveProjectPath(rest: String, originalSpec: String): FileResolution {
        val roots = currentProjectFileRoots(create = false)
        if (roots.isEmpty()) {
            error("'$originalSpec' needs a workspace project, but document '${session.documentName}' is not in one")
        }

        if (hasGlob(rest)) {
            return searchFirst(roots, rest)
                ?: error("No file matched '$originalSpec' in ${roots.joinToString { it.toString() }}")
        }

        for (root in roots) {
            val candidate = resolveChild(root.path, rest)
            if (Files.exists(candidate)) return FileResolution(candidate, root.source)
        }

        if (roots.size > 1) {
            error("'$originalSpec' is ambiguous because document '${session.documentName}' is in multiple projects")
        }

        val root = roots.single().also { Files.createDirectories(it.path) }
        return FileResolution(resolveChild(root.path, rest), root.source)
    }

    private fun resolveInRoot(
        root: Path,
        rest: String,
        originalSpec: String,
        createRoot: Boolean,
        source: String = "document"
    ): FileResolution {
        if (createRoot) Files.createDirectories(root)
        if (rest.isBlank()) return FileResolution(root, source)
        if (hasGlob(rest)) {
            return searchFirst(listOf(FileSearchRoot(source, root)), rest)
                ?: error("No file matched '$originalSpec' under $root")
        }
        return FileResolution(resolveChild(root, rest), source)
    }

    private fun searchRoots(): List<FileSearchRoot> {
        return listOf(FileSearchRoot("document", documentFilesDirectory)) + currentProjectFileRoots(create = false)
    }

    private fun currentProjectFilesDirectories(create: Boolean): List<Path> {
        return currentProjectFileRoots(create).map { it.path }
    }

    private fun currentProjectFileRoots(create: Boolean): List<FileSearchRoot> {
        val workspace = documentDirectory.parent ?: return emptyList()
        val projects = runCatching { listWorkspaceProjects(workspace.toString()) }.getOrDefault(emptyList())
        return projects
            .filter { project -> project.documents.contains(session.documentName) }
            .map { project ->
                FileSearchRoot(
                    source = "project:${project.name}",
                    path = projectsDir(workspace.toString()).toPath().resolve(project.name).resolve("files").normalize()
                )
            }
            .also { roots ->
                if (create) roots.forEach { Files.createDirectories(it.path) }
            }
    }

    private fun searchFirst(roots: List<FileSearchRoot>, pattern: String): FileResolution? {
        val normalizedPattern = pattern.replace('\\', '/')
        val alternatePattern = normalizedPattern.takeIf { it.startsWith("**/") }?.removePrefix("**/")

        for (root in roots.distinctBy { it.path }) {
            if (!Files.isDirectory(root.path)) continue
            val matcher = root.path.fileSystem.getPathMatcher("glob:$normalizedPattern")
            val alternateMatcher = alternatePattern?.let { root.path.fileSystem.getPathMatcher("glob:$it") }
            walk(root.path).use { stream ->
                val match = stream
                    .filter { Files.isRegularFile(it) }
                    .sorted(compareBy<Path> { it.toString() })
                    .filter { path ->
                        val relative = root.path.relativize(path)
                        matcher.matches(relative) || alternateMatcher?.matches(relative) == true
                    }
                    .findFirst()
                if (match.isPresent) return FileResolution(match.get().toAbsolutePath().normalize(), root.source)
            }
        }

        return null
    }

    private fun walk(root: Path): Stream<Path> {
        return runCatching { Files.walk(root) }
            .getOrElse { error("Couldn't search $root: ${it.message}") }
    }

    private fun resolveChild(root: Path, child: String): Path {
        val cleanChild = child.replace('\\', '/').trimStart('/')
        val target = root.resolve(cleanChild).normalize()
        require(target.startsWith(root.normalize())) {
            "Path '$child' escapes FileManager root $root"
        }
        return target
    }

    private fun refreshGlobalStateIfNeeded() {
        val current = FileManager.globalVersion()
        if (current != seenGlobalVersion) {
            resolved.clear()
            seenGlobalVersion = current
        }
    }

    private fun resetSessionAccessHistory() {
        synchronized(session.root) {
            session.root.properties[FILE_MANAGER_ACCESS_HISTORY_PROPERTY] = emptyList<Map<String, Any?>>()
        }
    }

    private fun recordAccess(spec: String, access: String, resolution: FileResolution) {
        val box = Box.context.get()
        val entry = linkedMapOf<String, Any?>(
            "index" to sequence.incrementAndGet(),
            "time" to System.currentTimeMillis(),
            "access" to access,
            "request" to spec,
            "resolvedPath" to resolution.path.toAbsolutePath().normalize().toString(),
            "source" to resolution.source,
            "exists" to Files.exists(resolution.path),
            "box" to box?.id,
            "boxName" to box?.name
        )

        synchronized(session.root) {
            val existing = session.root.properties[FILE_MANAGER_ACCESS_HISTORY_PROPERTY] as? List<*>
            val next = (existing.orEmpty().filterIsInstance<Map<String, Any?>>() + entry)
                .takeLast(FileManager.maxAccessHistoryEntries)
            session.root.setPropertyRaw(FILE_MANAGER_ACCESS_HISTORY_PROPERTY, next, doResync = false)
        }
        session.markSidecarPending(session.root.id, FILE_MANAGER_ACCESS_HISTORY_PROPERTY)
    }

    private data class FileSearchRoot(val source: String, val path: Path)

    private data class FileResolution(val path: Path, val source: String)
}

/**
 * File-related utilities for the active AField session.
 *
 * Resolves document/project-relative paths, named `%directory` mappings, globs,
 * and absolute or home-relative paths while keeping a small per-session cache.
 */
object FileManager {
    private val managers = Collections.synchronizedMap(WeakHashMap<SessionInformation, FieldFileManager>())
    private val namedDirectories = ConcurrentHashMap<String, Path>()
    private val version = AtomicLong(0)

    var maxAccessHistoryEntries: Int = 1024

    fun current(): FieldFileManager {
        val box = Box.context.get()
        val session = when {
            box != null && box.isInsideInitialized() -> box.inside
            else -> thisSessionInfo.get()
        } ?: error("FileManager needs an active Field session or Box context")

        return synchronized(managers) {
            managers.getOrPut(session) { FieldFileManager(session) }
        }
    }

    fun forSession(session: SessionInformation): FieldFileManager {
        return synchronized(managers) {
            managers.getOrPut(session) { FieldFileManager(session) }
        }
    }

    operator fun set(name: String, directory: String) {
        add(name, directory)
    }

    operator fun set(name: String, directory: File) {
        add(name, directory)
    }

    operator fun set(name: String, directory: Path) {
        add(name, directory)
    }

    operator fun get(name: String): Path? = namedDirectory(name)

    fun add(name: String, directory: String, create: Boolean = false): Path = add(name, expandHome(directory), create)

    fun add(name: String, directory: File, create: Boolean = false): Path = add(name, directory.toPath(), create)

    fun add(name: String, directory: Path, create: Boolean = false): Path {
        val cleanName = validateName(name)
        val root = directory.toAbsolutePath().normalize()
        if (create) Files.createDirectories(root)
        require(Files.isDirectory(root)) { "FileManager directory '$cleanName' is not a directory: $root" }
        namedDirectories[cleanName] = root
        version.incrementAndGet()
        return root
    }

    fun remove(name: String): Boolean {
        val removed = namedDirectories.remove(validateName(name)) != null
        if (removed) version.incrementAndGet()
        return removed
    }

    fun clearCache() {
        synchronized(managers) {
            managers.values.forEach { it.clearCache() }
        }
    }

    internal fun namedDirectory(name: String): Path? = namedDirectories[validateName(name)]

    internal fun globalVersion(): Long = version.get()

    private fun validateName(name: String): String {
        val clean = name.removePrefix("%").removePrefix("$").trim()
        require(clean.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) {
            "Invalid FileManager directory name '$name'"
        }
        require(clean != "document" && clean != "project") {
            "'$clean' is reserved by FileManager"
        }
        return clean
    }
}

val files: FieldFileManager
    get() = FileManager.current()

val fileManager: FieldFileManager
    get() = FileManager.current()

val String.file: File
    get() = FileManager.current().file(this)

val String.path: Path
    get() = FileManager.current().path(this)

val String.absolutePath: String
    get() = FileManager.current().absolutePath(this)

val String.abslutePath: String
    get() = absolutePath

private fun splitMappedPath(spec: String): Pair<String, String> {
    val withoutPrefix = spec.removePrefix("%")
    val separator = withoutPrefix.indexOfAny(charArrayOf('/', '\\'))
    if (separator < 0) return withoutPrefix to ""
    return withoutPrefix.substring(0, separator) to withoutPrefix.substring(separator + 1)
}

private fun hasGlob(spec: String): Boolean {
    return spec.any { it == '*' || it == '?' || it == '[' || it == '{' }
}

private fun isAbsoluteLike(spec: String): Boolean {
    return spec.startsWith("~/") || Paths.get(spec).isAbsolute
}

private fun expandHome(spec: String): Path {
    return if (spec == "~" || spec.startsWith("~/")) {
        Paths.get(System.getProperty("user.home")).resolve(spec.removePrefix("~/")).normalize()
    } else {
        Paths.get(spec)
    }
}
