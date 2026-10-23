package afield.document.workspace

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * The machine-wide registry of kit packages, and the immutable snapshots it installs them into.
 *
 * A kit package is a directory with a [KitPackageManifest] in its `field-kit.json`: one or more
 * starting points, each an ordinary Field document directory, together with whatever Kotlin
 * extension source, JARs, pinned Maven coordinates and native libraries those documents need.
 * [KitPackages.install] validates the manifest, hashes every file the manifest reaches, copies them
 * under [KitPackages.directory] (`~/.afield/kits` unless `afield.kits.dir` or `AFIELD_KITS_DIR`
 * says otherwise) and rehashes the copy before registering it. A revision is the hash of that file
 * inventory, so an existing revision is never rewritten and documents created from it keep the
 * bytes they were created from. No code from the package runs here.
 *
 * Registration writes two files beside the snapshots. `registry.json` is what
 * [KitPackages.installed] and Welcome read. `runtime.properties` is for `build.gradle.kts`, which
 * turns it into the next launch's extension roots, classpath, `java.library.path` and `-D`
 * properties, so a kit's runtime reaches Field through a restart rather than through the running
 * host. [KitPackages.active] reads back the revisions this process did launch with, which is how
 * [KitPackages.statuses] tells an active kit from one waiting for a restart.
 *
 * Two rules keep a package self-contained and its classpath buildable. [KitPackages.child]
 * resolves every manifest path canonically and refuses one that leaves the package; a symbolic
 * link under any path the manifest names is refused outright. And a second kit may not contradict
 * the first: conflicting versions of one Maven coordinate, two values for one system property, or
 * two different native libraries with the same filename each fail registration rather than the
 * launch.
 */

/** A self-contained runtime package. Each starting point is an ordinary Field document directory. */
@Serializable
data class KitPackageManifest(
    val schema: String = "field.kit.package.v1",
    val id: String,
    val title: String,
    val description: String = "",
    val version: String,
    val guide: String = "",
    val runtime: KitPackageRuntime = KitPackageRuntime(),
    val startingPoints: Map<String, KitStartingPoint> = emptyMap(),
    val sharedResources: String = "",
    val preferences: Map<String, String> = emptyMap(),
    val host: KitDedicatedHost? = null
)

/** Runtime declarations are consumed at the next build/restart, never injected into a running host. */
@Serializable
data class KitPackageRuntime(
    val extensionDirs: List<String> = emptyList(),
    val jars: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val nativeDirectories: Map<String, List<String>> = emptyMap(),
    val systemProperties: Map<String, String> = emptyMap(),
    val lifecycle: String = ""
)

/** A named, versioned seed; [launch] names boxes to launch in order on first open and explicit activation. */
@Serializable
data class KitStartingPoint(val title: String, val path: String, val description: String = "", val launch: List<String> = emptyList())

/** One immutable package snapshot registered on this machine. Old revisions remain for existing instances. */
@Serializable
data class InstalledKit(val id: String, val revision: String, val runtimeRevision: String, val directory: String, val source: String)

/** One row of Welcome's kit list: `active`, `restart-required` or `error`, with the reason. */
@Serializable
data class KitPackageStatus(
    val installed: InstalledKit,
    val manifest: KitPackageManifest?,
    val state: String,
    val message: String = ""
)

/** Registry and immutable snapshots used by Welcome and by the launcher's Gradle runtime profile. */
object KitPackages {
    const val MANIFEST = "field-kit.json"
    private val mapper = jacksonObjectMapper()
    private val safeName = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
    private val skipped = setOf(".git", "backups", "backup", ".field-kit-instance.json", ".field-kit-first-open", "versions.edn", ".DS_Store")
    val directory: File get() = File(System.getProperty("afield.kits.dir")
        ?: System.getenv("AFIELD_KITS_DIR") ?: File(System.getProperty("user.home"), ".afield/kits").path)

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

    fun runtimeRevision(root: File): String = runtimeHash(root, read(root))
    fun hashText(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

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

    fun writeJson(file: File, value: Any) {
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp-" + java.util.UUID.randomUUID())
        try { mapper.writerWithDefaultPrettyPrinter().writeValue(temp, value); Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        finally { temp.delete() }
    }

    fun active(): Map<String, String> = System.getProperty("afield.kit.revisions").orEmpty().split(',').filter { '@' in it }
        .associate { it.substringBefore('@') to it.substringAfter('@') }

    fun statuses(errors: Map<String, String> = emptyMap()): List<KitPackageStatus> {
        val active = active()
        return installed().map { kit ->
            runCatching { KitPackageStatus(kit, read(File(kit.directory)),
                when { kit.id in errors -> "error"; active[kit.id] == kit.revision -> "active"; else -> "restart-required" }, errors[kit.id].orEmpty()) }
                .getOrElse { KitPackageStatus(kit, null, "error", it.message.orEmpty()) }
        }
    }

    fun platform(): String {
        val os = System.getProperty("os.name").lowercase().let { when { "mac" in it || "darwin" in it -> "macos"; "win" in it -> "windows"; else -> "linux" } }
        val arch = System.getProperty("os.arch").lowercase().let { if (it in listOf("aarch64", "arm64")) "arm64" else "x64" }
        return "$os-$arch"
    }
}
