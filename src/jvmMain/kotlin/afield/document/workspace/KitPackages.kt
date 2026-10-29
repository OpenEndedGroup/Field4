package afield.document.workspace

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * The registry of kit packages installed on this machine, and the immutable snapshot each
 * registration copies out of a package's source folder. A kit package is a directory holding a
 * `field-kit.json` that names one or more starting points, each of them an ordinary saved Field
 * document, beside optional Kotlin sources, JARs, pinned Maven coordinates, native libraries and a
 * lifecycle class. `public/docs/kits.md` is the user's page. The smaller role-kit model, which
 * describes shared box code and assets inside one document, is [Kits].
 *
 * [KitPackages.read] validates a manifest and [KitPackages.install] copies the package to
 * `packages/<id>/<revision>/` under `~/.afield/kits` (`afield.kits.dir` or `AFIELD_KITS_DIR` moves
 * that root). The revision is a SHA-256 over the path and content hash of every declared file, so
 * registering unchanged source resolves to the snapshot that already exists, and a document goes on
 * reading the revision it recorded after a newer one arrives. Installation reads JSON and copies
 * bytes; it runs nothing the package ships.
 *
 * Two files carry the registry to the next start of Field. `registry.json` lists the
 * [InstalledKit] snapshots, and `runtime.properties` is a launch profile that `build.gradle.kts`
 * reads to assemble the extension classpath, resolve the pinned dependencies, extend
 * `java.library.path` and pass `-Dafield.kit.packages` and `-Dafield.kit.revisions` to the host,
 * which is where [KitRuntime] picks the same set up again. A kit registered inside a running host
 * therefore reports `restart-required` from [KitPackages.statuses] until its revision turns up in
 * [KitPackages.active]; those statuses are what Welcome's **Kits** area lists.
 *
 * [KitPackages.child] is the containment rule every path here goes through: package-relative, no
 * `..`, and canonicalised, so neither a path nor a symlink inside the package can reach a file
 * outside it. Because one host loads every installed runtime together, installing also refuses a
 * set of kits that disagree about a dependency's version, about the value of a runtime system
 * property, or about the contents of two native libraries with the same filename.
 */

/** A self-contained runtime package. Each starting point is an ordinary Field document directory. */
@Serializable
data class KitPackageManifest(
    /** Pinned at `field.kit.package.v1`. [KitPackages.read] refuses a manifest declaring any other schema. */
    val schema: String = "field.kit.package.v1",
    /**
     * Names the kit wherever one is named: the `packages/<id>/` snapshot folder, the registry entry, the
     * `kit-<id>` workspace project and `-Dafield.kit.revisions`. A letter or digit, then up to 127 more
     * characters from `A-Za-z0-9._-`.
     */
    val id: String,
    /** The kit's name in Welcome's kit library, which `read` requires to be non-blank. */
    val title: String,
    /** Prose for the library entry, and what `GET /api/kits/guide` renders when [guide] is blank. */
    val description: String = "",
    /**
     * The kit author's own version string. `read` requires it to be non-blank and nothing else reads it: a
     * snapshot is keyed by [InstalledKit.revision], a hash of the package's contents.
     */
    val version: String,
    /**
     * Package-relative path to a markdown file served as the kit's guide. `read` requires the file to exist
     * when this is set, and [description] stands in when it is blank.
     */
    val guide: String = "",
    /**
     * The classpath, dependency, native-library and lifecycle declarations, which reach a host through the
     * `runtime.properties` launch profile rather than the registry, so registering a kit adds nothing to the
     * host that registered it.
     */
    val runtime: KitPackageRuntime = KitPackageRuntime(),
    /**
     * The documents this kit offers to copy, keyed by a name of the same shape as [id]. `read` requires at
     * least one, and requires each [KitStartingPoint.path] to hold a Field document.
     */
    val startingPoints: Map<String, KitStartingPoint> = emptyMap(),
    /**
     * Package-relative directory of files the kit's documents read at run time.
     * [KitInstances.sharedResources] resolves it against the revision a document recorded, not against the
     * newest installed one.
     */
    val sharedResources: String = "",
    /**
     * Seeds `preferences.json` for the `kit-<id>` project. [KitInstances.create] writes it when it creates
     * that project, so a later revision's changed preferences do not reach a project that already exists.
     */
    val preferences: Map<String, String> = emptyMap(),
    /**
     * Set for a kit that brings its own launcher and workspace ([KitDedicatedHost]). Such a kit contributes
     * no runtime to the calling host: [KitRuntime.start] skips it and the launch profile records an empty
     * [KitPackageRuntime] in its place.
     */
    val host: KitDedicatedHost? = null
)

/** Runtime declarations are consumed at the next build/restart, never injected into a running host. */
@Serializable
data class KitPackageRuntime(
    /**
     * Package-relative directories of Kotlin sources and resources compiled into the personal class layer by
     * the next build. `read` requires each to hold a `kotlin/` or a `resources/` folder.
     */
    val extensionDirs: List<String> = emptyList(),
    /** Package-relative JAR files added to the extension classpath; `read` requires each to exist. */
    val jars: List<String> = emptyList(),
    /**
     * Maven coordinates the launcher's Gradle build resolves. `read` refuses a `SNAPSHOT` or a `latest`
     * version, and installing refuses a set of kits asking for two versions of one `group:artifact`.
     */
    val dependencies: List<String> = emptyList(),
    /**
     * Native library directories per platform, keyed as [KitPackages.platform] spells it (`macos-arm64`,
     * `linux-x64` and so on). Only the running platform's entry is written to the launch profile, and two
     * kits may not supply different bytes under one library filename.
     */
    val nativeDirectories: Map<String, List<String>> = emptyMap(),
    /**
     * System properties the launcher passes to the host, with `${kit}` replaced by the snapshot directory.
     * `read` rejects a key beginning `afield.kit` or naming a classpath property, and two kits may not ask
     * for different values of one key.
     */
    val systemProperties: Map<String, String> = emptyMap(),
    /**
     * Fully qualified name of a [KitLifecycle] class, instantiated from the personal class layer when the
     * host starts. Empty leaves the kit with a lifecycle whose four methods do nothing.
     */
    val lifecycle: String = ""
)

/** A named, versioned seed; [launch] names boxes to launch in order on first open and explicit activation. */
@Serializable
data class KitStartingPoint(
    /** This starting point's name in the kit library, which `read` requires to be non-blank. */
    val title: String,
    /**
     * Package-relative directory of the seed document, which `read` requires to hold a `metadata.edn` or a
     * `metadata.json`.
     */
    val path: String,
    /** Optional prose about this starting point. */
    val description: String = "",
    /**
     * Box names to launch in this order, read back by [KitRuntime.launchBoxes]. `read` requires them
     * non-blank and distinct, and launching requires exactly one live box per name.
     */
    val launch: List<String> = emptyList()
)

/** One immutable package snapshot registered on this machine. Old revisions remain for existing instances. */
@Serializable
data class InstalledKit(
    /** The manifest's [KitPackageManifest.id], which is also the registry key this entry replaces. */
    val id: String,
    /**
     * Hash over the path and content of every file the manifest declares, and the name of the snapshot
     * folder. Registering unchanged source resolves to the snapshot that already exists.
     */
    val revision: String,
    /**
     * Hash over the runtime declarations and the files they name alone, so two revisions that differ only
     * in a starting point share it. [KitRuntime.activate] refuses a document recorded against another one.
     */
    val runtimeRevision: String,
    /** Canonical path of the installed snapshot, under `packages/<id>/<revision>/` in the kits directory. */
    val directory: String,
    /**
     * The folder the package was registered from, which Welcome's **Update** registers again, and matches
     * against the bundled starter kits to leave out one that is already installed.
     */
    val source: String
)

/** One registered kit as Welcome lists it: the registry entry, its manifest, and whether this host runs it. */
@Serializable
data class KitPackageStatus(
    /** The registry entry this status is about. */
    val installed: InstalledKit,
    /** The snapshot's manifest, or null when reading it threw, which is also when [state] is `error`. */
    val manifest: KitPackageManifest?,
    /**
     * `active` when this host loaded this revision, `error` when startup or a read failed, and
     * `restart-required` for a registered revision this host has not loaded.
     */
    val state: String,
    /** The failure, when [state] is `error`, and empty otherwise. */
    val message: String = ""
)

/** Registry and immutable snapshots used by Welcome and by the launcher's Gradle runtime profile. */
object KitPackages {
    /** The manifest filename a kit package folder is recognised by. */
    const val MANIFEST = "field-kit.json"
    private val mapper = jacksonObjectMapper()
    private val safeName = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
    private val skipped = setOf(".git", "backups", "backup", ".field-kit-instance.json", ".field-kit-first-open", "versions.edn", ".DS_Store")

    /**
     * Where the registry, the launch profile and the installed snapshots live: the `afield.kits.dir` system
     * property, else `AFIELD_KITS_DIR`, else `~/.afield/kits`. Read on each access, so a property set after
     * start takes effect, and the folder is created when something writes to it rather than here.
     */
    val directory: File get() = File(System.getProperty("afield.kits.dir")
        ?: System.getenv("AFIELD_KITS_DIR") ?: File(System.getProperty("user.home"), ".afield/kits").path)

    /**
     * Reads `field-kit.json` from [root] and validates every declaration in it, throwing with the first
     * problem it finds rather than returning a partial manifest. Each path is checked for containment and
     * for the file or folder it promises, so a manifest that reads is one whose package is complete.
     */
    fun read(root: File): KitPackageManifest {
        val manifest = mapper.readValue(File(root, MANIFEST), KitPackageManifest::class.java)
        require(manifest.schema == "field.kit.package.v1") { "Unsupported kit package schema: ${manifest.schema}" }
        require(safeName.matches(manifest.id) && manifest.title.isNotBlank() && manifest.version.isNotBlank()) { "A kit needs an id, title and version" }
        require(manifest.startingPoints.isNotEmpty()) { "A kit needs at least one starting point" }
        manifest.host?.let { KitDedicatedHosts.validate(root, it) }
        manifest.startingPoints.forEach { (id, point) ->
            require(safeName.matches(id) && point.title.isNotBlank()) { "Invalid starting point: $id" }
            val seed = child(root, point.path)
            require(File(seed, "metadata.edn").isFile || File(seed, "metadata.json").isFile) { "Starting point '$id' has no Field document at ${point.path}" }
            require(point.launch.distinct().size == point.launch.size && point.launch.all { it.isNotBlank() }) { "Starting point '$id' has invalid launch names" }
        }
        manifest.runtime.extensionDirs.forEach {
            val dir = child(root, it)
            require(File(dir, "kotlin").isDirectory || File(dir, "resources").isDirectory) { "Extension $it needs kotlin/ or resources/" }
        }
        manifest.runtime.jars.forEach { require(child(root, it).let { f -> f.isFile && f.extension == "jar" }) { "Missing JAR: $it" } }
        manifest.runtime.dependencies.forEach { coordinate ->
            require(Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)?").matches(coordinate) &&
                !coordinate.contains("SNAPSHOT", true) && !coordinate.split(':')[2].startsWith("latest", true)) { "Use a pinned Maven coordinate: $coordinate" }
        }
        manifest.runtime.nativeDirectories.values.flatten().forEach { require(child(root, it).isDirectory) { "Missing native directory: $it" } }
        manifest.runtime.systemProperties.forEach { (key, _) ->
            require(Regex("[A-Za-z][A-Za-z0-9_.-]+").matches(key) && !key.startsWith("afield.kit") &&
                key !in setOf("afield.personal.classpath", "user.dir", "java.class.path", "java.library.path")) { "Reserved or invalid runtime property: $key" }
        }
        require(manifest.runtime.lifecycle.isEmpty() || Regex("[A-Za-z_$][A-Za-z0-9_$.]+").matches(manifest.runtime.lifecycle)) { "Invalid lifecycle class" }
        if (manifest.guide.isNotBlank()) require(child(root, manifest.guide).isFile) { "Missing kit guide" }
        if (manifest.sharedResources.isNotBlank()) require(child(root, manifest.sharedResources).isDirectory) { "Missing shared resources" }
        return manifest
    }

    /** Canonical containment applies to symlinks too; installed snapshots never depend on outside files. */
    fun child(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && path.split('/', '\\').none { it == ".." }) { "Kit paths must stay inside their package: $path" }
        val base = root.canonicalFile
        return File(base, path).canonicalFile.also { require(it.toPath().startsWith(base.toPath())) { "Kit path escapes its package: $path" } }
    }

    /** The SHA-256 of a file's bytes as 64 hex characters, read in 64 KiB blocks so package size does not matter. */
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(files: Map<String, String>): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(files.toSortedMap())).joinToString("") { "%02x".format(it) }

    private fun inventory(root: File, roots: List<File>): Map<String, String> {
        val paths = sortedMapOf<String, String>()
        roots.forEach { start ->
            val entries = if (start.isDirectory) start.walkTopDown().onEnter {
                require(!Files.isSymbolicLink(it.toPath())) { "Kit directories must be self-contained: $it" }
                it.name !in skipped
            }.filter { it.isFile } else sequenceOf(start)
            entries.forEach { file ->
                if (file.name !in skipped) {
                    require(!Files.isSymbolicLink(file.toPath())) { "Kit files must be self-contained: $file" }
                    val relative = file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
                    child(root, relative)
                    paths[relative] = hash(file)
                    require(paths.size <= 30000) { "Kit contains more than 30000 files" }
                }
            }
        }
        return paths
    }

    private fun roots(root: File, m: KitPackageManifest): List<File> = listOf(File(root, MANIFEST)) +
        m.startingPoints.values.map { child(root, it.path) } + runtimeRoots(root, m) +
        listOf(m.guide, m.sharedResources, m.host?.launcher.orEmpty()).filter { it.isNotBlank() }.map { child(root, it) }

    private fun runtimeRoots(root: File, m: KitPackageManifest): List<File> =
        (m.runtime.extensionDirs + m.runtime.jars + m.runtime.nativeDirectories.values.flatten()).map { child(root, it) }

    private fun runtimeHash(root: File, m: KitPackageManifest): String = digest(inventory(root, runtimeRoots(root, m)) +
        ("runtime" to mapper.writeValueAsString(m.runtime)))

    /**
     * The [InstalledKit.runtimeRevision] a package folder would register under, covering the runtime
     * declarations and the files they name. Reads and validates the manifest on the way, so it throws on a
     * package [read] would reject.
     */
    fun runtimeRevision(root: File): String = runtimeHash(root, read(root))

    /**
     * The SHA-256 of a string's bytes as hex. Its one use is the identity of a [WorkspaceProjectScope]'s
     * box, which hashes the project directory's canonical path.
     */
    fun hashText(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** The registry as `registry.json` has it, one entry per kit id, and empty when nothing is registered. */
    @Synchronized
    fun installed(): List<InstalledKit> {
        val registry = File(directory, "registry.json")
        if (!registry.isFile) return emptyList()
        return mapper.readValue(registry, mapper.typeFactory.constructCollectionType(List::class.java, InstalledKit::class.java))
    }

    /** Validate and copy a package before publishing its new registry entry. No code executes here. */
    @Synchronized
    fun install(source: File): InstalledKit = locked {
        val root = source.canonicalFile
        val manifest = read(root)
        val files = inventory(root, roots(root, manifest))
        val revision = digest(files)
        val destination = File(directory, "packages/${manifest.id}/$revision")
        val temporary = File(directory, ".install-${java.util.UUID.randomUUID()}")
        temporary.mkdirs()
        try {
            files.forEach { (relative, expected) ->
                val to = File(temporary, relative); to.parentFile.mkdirs(); child(root, relative).copyTo(to)
                require(hash(to) == expected) { "Kit changed while installing: $relative" }
            }
            require(read(temporary) == manifest) { "Kit manifest changed during installation" }
            if (!destination.exists()) { destination.parentFile.mkdirs(); Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            require(files.all { (relative, expected) -> hash(child(destination, relative)) == expected }) { "Installed package snapshot was changed" }
            val result = InstalledKit(manifest.id, revision, runtimeHash(destination, manifest), destination.canonicalPath, root.path)
            val next = installed().filterNot { it.id == result.id } + result
            validateDependencies(next)
            save(next)
            result
        } finally { temporary.deleteRecursively() }
    }

    private fun validateDependencies(kits: List<InstalledKit>) {
        val versions = mutableMapOf<String, String>()
        val properties = mutableMapOf<String, String>()
        val natives = mutableMapOf<String, String>()
        kits.forEach { kit ->
            val manifest = read(File(kit.directory))
            if (manifest.host != null) return@forEach
            manifest.runtime.dependencies.forEach { coordinate ->
                val key = coordinate.split(':').take(2).joinToString(":")
                val old = versions.put(key, coordinate)
                require(old == null || old == coordinate) { "Kits request conflicting dependencies: $old and $coordinate" }
            }
            manifest.runtime.systemProperties.forEach { (key, raw) ->
                val value = raw.replace("\${kit}", kit.directory)
                val previous = properties.put(key, value)
                require(previous == null || previous == value) { "Kits request different values for runtime property $key" }
            }
            manifest.runtime.nativeDirectories[platform()].orEmpty().forEach { path ->
                child(File(kit.directory), path).listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                    val digest = hash(file); val previous = natives.put(file.name, digest)
                    require(previous == null || previous == digest) { "Kits supply conflicting native library ${file.name}" }
                }
            }
        }
    }

    /**
     * Drops a kit from the registry and rewrites the launch profile without it. The installed snapshots stay
     * on disk and so do the documents made from them, so a host that has already loaded the kit goes on
     * running it until it restarts.
     */
    @Synchronized
    fun remove(id: String) = locked { save(installed().filterNot { it.id == id }) }

    private fun <T> locked(block: () -> T): T {
        directory.mkdirs()
        return java.nio.channels.FileChannel.open(File(directory, "registry.lock").toPath(),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun save(kits: List<InstalledKit>) {
        val properties = Properties()
        properties["count"] = kits.size.toString()
        kits.sortedBy { it.id }.forEachIndexed { index, kit ->
            val m = read(File(kit.directory)); val p = "kit.$index."
            properties[p + "id"] = kit.id; properties[p + "revision"] = kit.revision; properties[p + "directory"] = kit.directory
            val integrity = inventory(File(kit.directory), roots(File(kit.directory), m))
            require(digest(integrity) == kit.revision) { "Installed package snapshot was changed: ${kit.id}" }
            properties[p + "files.count"] = integrity.size.toString()
            integrity.entries.forEachIndexed { i, (path, hash) -> properties[p + "files.$i.path"] = path; properties[p + "files.$i.hash"] = hash }
            fun paths(name: String, values: List<String>, absolute: Boolean = true) {
                properties[p + name + ".count"] = values.size.toString()
                values.forEachIndexed { i, value -> properties[p + name + ".$i"] = if (absolute) child(File(kit.directory), value).path else value }
            }
            // Existing launchers own their runtime setup. Listing their requirements never injects them into another host.
            val runtime = if (m.host == null) m.runtime else KitPackageRuntime()
            paths("extensions", runtime.extensionDirs); paths("jars", runtime.jars); paths("dependencies", runtime.dependencies, false)
            paths("natives", runtime.nativeDirectories[platform()].orEmpty())
            properties[p + "properties.count"] = runtime.systemProperties.size.toString()
            runtime.systemProperties.entries.forEachIndexed { i, (key, value) ->
                properties[p + "properties.$i.key"] = key
                properties[p + "properties.$i.value"] = value.replace("\${kit}", kit.directory)
            }
        }
        directory.mkdirs()
        val temp = File(directory, "runtime.properties.tmp")
        temp.outputStream().use { properties.store(it, "Field kit runtime profile; written by Welcome") }
        Files.move(temp.toPath(), File(directory, "runtime.properties").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        writeJson(File(directory, "registry.json"), kits)
    }

    /**
     * Writes [value] as pretty-printed JSON through a uniquely named temporary file and an atomic move, so a
     * reader of [file] sees either the previous contents or the new ones. Parent directories are created.
     */
    fun writeJson(file: File, value: Any) {
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp-" + java.util.UUID.randomUUID())
        try { mapper.writerWithDefaultPrettyPrinter().writeValue(temp, value); Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        finally { temp.delete() }
    }

    /**
     * The revision this host actually loaded for each kit id, parsed from the `afield.kit.revisions` the
     * launcher passed in as `id@revision` pairs. Empty in a host the launcher did not start, which is what
     * leaves every registered kit reporting `restart-required`.
     */
    fun active(): Map<String, String> = System.getProperty("afield.kit.revisions").orEmpty().split(',').filter { '@' in it }
        .associate { it.substringBefore('@') to it.substringAfter('@') }

    /**
     * One [KitPackageStatus] per registered kit, which is what Welcome's **Kits** area lists. Pass
     * [KitRuntime.errors] as [errors] to have a kit whose startup threw report `error` with its message;
     * a kit whose snapshot will not read reports `error` too, with a null manifest.
     */
    fun statuses(errors: Map<String, String> = emptyMap()): List<KitPackageStatus> {
        val active = active()
        return installed().map { kit ->
            runCatching { KitPackageStatus(kit, read(File(kit.directory)),
                when { kit.id in errors -> "error"; active[kit.id] == kit.revision -> "active"; else -> "restart-required" }, errors[kit.id].orEmpty()) }
                .getOrElse { KitPackageStatus(kit, null, "error", it.message.orEmpty()) }
        }
    }

    /**
     * How this machine is spelled in [KitPackageRuntime.nativeDirectories]: one of `macos`, `windows` or
     * `linux`, a hyphen, then `arm64` or `x64`. Anything that is not macOS or Windows reads as `linux`, and
     * anything that is not AArch64 reads as `x64`.
     */
    fun platform(): String {
        val os = System.getProperty("os.name").lowercase().let { when { "mac" in it || "darwin" in it -> "macos"; "win" in it -> "windows"; else -> "linux" } }
        val arch = System.getProperty("os.arch").lowercase().let { if (it in listOf("aarch64", "arm64")) "arm64" else "x64" }
        return "$os-$arch"
    }
}
