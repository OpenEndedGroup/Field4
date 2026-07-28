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

/**
 * The property on the session root that accumulates every file lookup this session has made — request, resolved
 * path, which root answered it, whether it existed, and which box asked.
 *
 * Reset when the session's file manager is created and trimmed to [FileManager.maxAccessHistoryEntries], so it is
 * a recent-activity log rather than a complete one. Read it when a path resolved somewhere you did not expect and
 * you want to see where it actually went.
 */
const val FILE_MANAGER_ACCESS_HISTORY_PROPERTY = "fileManagerAccessHistory"

/**
 * One session's view of the filesystem: hand it a short spec and it gives you a real `File` or `Path`.
 *
 * Reach for it as [files] rather than constructing one. Resolution is tried in a fixed order — absolute and `~/`
 * paths; `%document/…` and `%project/…`; any name registered with [FileManager.add]; a glob, matched by walking the
 * search roots and taking the **first sorted** match; and otherwise a plain relative name looked for in the
 * document's `files/` directory and then in each workspace project the document belongs to.
 *
 * Two things worth knowing. A resolution is cached only **if the file exists**, so a name you resolve before
 * creating the file is re-resolved next time rather than being stuck; and a spec that would climb out of its root
 * is rejected outright, so `%document/../..` throws rather than escaping.
 *
 * Every lookup is recorded to [FILE_MANAGER_ACCESS_HISTORY_PROPERTY], and every call needs a live session — there
 * is no way to use this outside one.
 */
class FieldFileManager internal constructor(private val session: SessionInformation) {
    private val resolved = ConcurrentHashMap<String, FileResolution>()
    private val sequence = AtomicLong(0)
    private var seenGlobalVersion = FileManager.globalVersion()

    init {
        resetSessionAccessHistory()
    }

    /** The directory this document lives in — a directory, not a single file, since a Field document is a folder. */
    val documentDirectory: Path
        get() = File(session.filename).absoluteFile.toPath().normalize()

    /**
     * This document's own `files/` directory, the first place a relative name is looked for.
     *
     * **Created on read** if it does not exist, so merely asking for it has a side effect on disk.
     */
    val documentFilesDirectory: Path
        get() = documentDirectory.resolve("files").normalize().also { Files.createDirectories(it) }

    /**
     * The `files/` directory of every workspace project this document belongs to — where a name not found in the
     * document itself is looked for next. Empty when the document is in no project.
     *
     * Like [documentFilesDirectory], reading this **creates** the directories.
     */
    val projectFilesDirectories: List<Path>
        get() = currentProjectFilesDirectories(create = true)

    /**
     * Resolves a spec to a `File`. The everyday call, usually written as `"data.csv".file`.
     *
     * The file need not exist: for a plain relative name that is nowhere to be found, you get the path it *would*
     * have inside the document's `files/`, which is what you want for something you are about to write. A glob or a
     * `%name/…` that matches nothing throws instead, since there is no sensible path to invent.
     *
     * @throws IllegalStateException on a blank spec, an unmatched glob, an unknown `%name`, or a `%project` spec in a
     * document that is in no project — or in more than one, ambiguously
     */
    fun file(spec: String): File = resolveAndRecord(spec, "file").toFile()

    /** The same resolution as [file], as an NIO `Path`. Prefer it when you are going on to use `java.nio.file`. */
    fun path(spec: String): Path = resolveAndRecord(spec, "path")

    /** The same resolution as [file], as an absolute normalised `String` — for handing a path to a subprocess. */
    fun absolutePath(spec: String): String = resolveAndRecord(spec, "absolutePath").toAbsolutePath().normalize().toString()

    /**
     * Forgets this session's cached resolutions. Rarely needed: the cache already drops a resolution whose file has
     * gone, and is cleared wholesale whenever the named-directory table changes.
     */
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

    /** How many lookups [FILE_MANAGER_ACCESS_HISTORY_PROPERTY] keeps before dropping the oldest. Applied to the
     * whole list on the next lookup, so lowering it *does* trim the history already recorded. */
    var maxAccessHistoryEntries: Int = 1024

    /**
     * The file manager for whatever session is in scope right now — the current box's, or the ambient session's.
     *
     * One manager per session, kept weakly, so it goes away with the session. This is what [files] and the `String`
     * extensions call.
     *
     * @throws IllegalStateException when there is neither an active Box context nor a session — which is what you get
     * calling this from a plain unit test or a bare thread
     */
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

    /** The manager for a named session rather than the ambient one — for code holding a session explicitly. */
    fun forSession(session: SessionInformation): FieldFileManager {
        return synchronized(managers) {
            managers.getOrPut(session) { FieldFileManager(session) }
        }
    }

    /**
     * `FileManager["textures"] = "~/Pictures"` — registers a named directory, so `%textures/wall.png` resolves.
     *
     * Sugar for [add] with `create = false`, so the directory must already exist. Registration is **process-global**
     * and outlives the document, and re-registering a name silently replaces it.
     */
    operator fun set(name: String, directory: String) {
        add(name, directory)
    }

    /** `FileManager["name"] = someFile` — as the `String` overload, taking a `File`. */
    operator fun set(name: String, directory: File) {
        add(name, directory)
    }

    /** `FileManager["name"] = somePath` — as the `String` overload, taking a `Path`. */
    operator fun set(name: String, directory: Path) {
        add(name, directory)
    }

    /**
     * The directory registered under this name, or null if nothing is registered under it. The `%` prefix is
     * optional — `get("x")` and `get("%x")` agree.
     *
     * A name is *validated*, not merely looked up, so this throws rather than returning null for a malformed
     * one and for the two reserved names the resolver owns: `FileManager["document"]` and
     * `FileManager["project"]` are `IllegalArgumentException`, not null.
     */
    operator fun get(name: String): Path? = namedDirectory(name)

    /** [add] from a string path, with `~` expanded first. */
    fun add(name: String, directory: String, create: Boolean = false): Path = add(name, expandHome(directory), create)

    /** [add] from a `File`. */
    fun add(name: String, directory: File, create: Boolean = false): Path = add(name, directory.toPath(), create)

    /**
     * Registers [directory] under [name] so `%name/…` resolves into it, returning the absolute path stored.
     *
     * Process-global and shared by every open document, so a name registered by one workspace affects them all, and
     * registering an existing name replaces it without warning. Pass `create = true` to make the directory rather
     * than require it.
     *
     * The name must look like an identifier (letters, digits, `_`, `-`, not starting with a digit); a leading `%` or
     * `$` is stripped for you. `document` and `project` are **reserved**, since they are the built-in roots.
     *
     * @throws IllegalArgumentException on a malformed or reserved name, or when the path is not a directory
     */
    fun add(name: String, directory: Path, create: Boolean = false): Path {
        val cleanName = validateName(name)
        val root = directory.toAbsolutePath().normalize()
        if (create) Files.createDirectories(root)
        require(Files.isDirectory(root)) { "FileManager directory '$cleanName' is not a directory: $root" }
        namedDirectories[cleanName] = root
        version.incrementAndGet()
        return root
    }

    /**
     * Unregisters a named directory, returning whether there was one. Nothing on disk is touched.
     *
     * @throws IllegalArgumentException on a malformed or reserved name, the same as [add]
     */
    fun remove(name: String): Boolean {
        val removed = namedDirectories.remove(validateName(name)) != null
        if (removed) version.incrementAndGet()
        return removed
    }

    /** Clears every session's resolution cache. Registering or removing a named directory already does this. */
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

/**
 * The current session's [FieldFileManager] — the way into all of this from a box: `files.file("data.csv")`.
 *
 * Resolved per read, so it always belongs to the session you are actually in.
 */
val files: FieldFileManager
    get() = FileManager.current()

/** Alias for [files], for code that reads better spelled out. */
val fileManager: FieldFileManager
    get() = FileManager.current()

/**
 * `"data.csv".file` — the terse form of `files.file(this)`, and the idiom worth learning first.
 *
 * Understands everything [FieldFileManager] does — a home-relative path, a `%document/…` or `%project/…` spec, a
 * registered `%name`, or a recursive glob. Throws on an unmatched glob or an unknown name; see [FieldFileManager.file].
 */
val String.file: File
    get() = FileManager.current().file(this)

/** `"data.csv".path` — the same resolution as [String.file], as an NIO `Path`. */
val String.path: Path
    get() = FileManager.current().path(this)

/** `"data.csv".absolutePath` — the same resolution, as an absolute normalised string. */
val String.absolutePath: String
    get() = FileManager.current().absolutePath(this)

/**
 * A **misspelling** of [absolutePath] that works anyway.
 *
 * Kept so that box code which typed it once keeps running, and deprecated rather than removed for the same reason:
 * a live-coded document is a file on someone's disk, not a call site that can be recompiled. Does nothing different.
 */
@Deprecated(
    "Misspelling of absolutePath, kept only so existing box code keeps running.",
    ReplaceWith("absolutePath"),
    DeprecationLevel.WARNING
)
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
