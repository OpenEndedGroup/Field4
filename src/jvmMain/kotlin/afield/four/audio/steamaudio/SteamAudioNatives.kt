package afield.four.audio.steamaudio

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Locates and loads the Steam Audio shared library.
 *
 * Loading happens exactly once per class loader, on first touch of any binding. Callers do
 * not normally need to invoke anything here -- [SteamAudioContext] triggers it -- but
 * [load] may be called explicitly to control *when* the library is loaded, or to surface a
 * loading failure early.
 *
 * The library is located by trying, in order:
 *
 *  1. The system property `steamaudio.library.file`, if set: an absolute path to the shared
 *     library itself.
 *  2. The system property `steamaudio.library.path`, if set: a directory containing the
 *     platform's libraries, laid out as in the vendored `native/` tree
 *     (`macos/`, `linux-x64/`, `windows-x64/`).
 *  3. Classpath resources under `/afield/four/audio/steamaudio/natives/<platform>/`, which is
 *     how the published `steamaudio-kotlin-natives-*` artifacts ship. Resources are extracted
 *     to a content-addressed cache directory and reused across JVM runs.
 *
 * Loading uses `System.load` on an absolute path, which places the library in the JVM's
 * loader lookup -- this is what the generated bindings resolve symbols against.
 *
 * ### Module path: put the natives jar on the classpath
 *
 * This library works as a named module, and that is worth doing -- it lets you write
 * `--enable-native-access=afield.four.audio.steamaudio` instead of opening native access to
 * everything with `ALL-UNNAMED`.
 *
 * The natives jars, though, belong on the **classpath** even then. They contain resources and
 * no code, so nothing `requires` them; on the module path they are never resolved and their
 * resources stay invisible. Verified both ways:
 *
 * ```
 * # works
 * java --module-path lib.jar:kotlin-stdlib.jar \
 *      --add-modules afield.four.audio.steamaudio \
 *      --enable-native-access=afield.four.audio.steamaudio \
 *      -cp app-classes:steamaudio-kotlin-natives-macos.jar MainClass
 *
 * # fails: the natives module is never resolved
 * java --module-path lib.jar:kotlin-stdlib.jar:steamaudio-kotlin-natives-macos.jar ...
 * ```
 *
 * Everything on the plain classpath works too, and is the common case.
 */
public object SteamAudioNatives {

    /** Identifies a supported platform and where its libraries live. */
    public enum class Platform(
        /** Directory name used in both the vendored tree and the resource layout. */
        public val directory: String,
        /** The main Steam Audio library's file name on this platform. */
        public val libraryFileName: String,
        /**
         * Libraries that must be loaded *before* the main library, in order.
         *
         * On Windows `phonon.dll` may resolve AMD's TrueAudio Next and GPU utility DLLs;
         * loading them explicitly first avoids depending on the OS search path finding them.
         */
        public val dependencies: List<String> = emptyList(),
    ) {
        // macOS, universal binary covering both x86-64 and arm64. Line comments rather than
        // KDoc: enum entries are not a declaration Field's reference extractor can read, so a
        // KDoc block here would be published under whatever parses next. See
        // dev_docs/kdoc_reference_contract.md.
        MACOS("macos", "libphonon.dylib"),

        // Linux on x86-64.
        LINUX_X64("linux-x64", "libphonon.so"),

        // Windows on x86-64.
        WINDOWS_X64("windows-x64", "phonon.dll", listOf("GPUUtilities.dll", "TrueAudioNext.dll")),
        ;

        /** Every library file this platform ships, dependencies first. */
        public val allFiles: List<String> get() = dependencies + libraryFileName
    }

    private const val RESOURCE_ROOT = "/afield/four/audio/steamaudio/natives"
    private const val PROPERTY_FILE = "steamaudio.library.file"
    private const val PROPERTY_PATH = "steamaudio.library.path"

    private val loaded = AtomicReference<Path?>(null)

    /**
     * The platform this JVM is running on.
     *
     * @throws UnsupportedOperationException if no Steam Audio binary exists for this
     *   os/arch pair. Notably, upstream ships no Linux arm64 build.
     */
    public val platform: Platform by lazy { detectPlatform() }

    /**
     * Loads the Steam Audio library if it has not already been loaded, and returns the path
     * it was loaded from. Idempotent and thread-safe.
     */
    public fun load(): Path {
        loaded.get()?.let { return it }
        synchronized(this) {
            loaded.get()?.let { return it }
            val path = resolveAndLoad()
            loaded.set(path)
            return path
        }
    }

    /** How to obtain the library, for any message that has to say what to do next. */
    public const val HOW_TO_FETCH: String =
        "Unpack this host's copy with `./gradlew fetchSteamAudioNatives` (needs the network once), " +
            "or point -PsteamaudioLibrary at a libphonon you built yourself."

    /**
     * Why [load] would fail if called right now, or `null` when the library is loadable.
     *
     * Resolves without loading, so a caller can decide whether to proceed. That is what lets
     * tests skip with a stated reason on a checkout that has never fetched the binaries, rather
     * than failing on an `UnsatisfiedLinkError` that names no remedy -- Field does not commit the
     * binaries, so that is the ordinary state of a fresh clone.
     */
    public val unavailableReason: String?
        get() {
            if (loaded.get() != null) return null

            val platform = try {
                platform
            } catch (unsupported: UnsupportedOperationException) {
                return unsupported.message ?: "no Steam Audio binary for this platform"
            }

            System.getProperty(PROPERTY_FILE)?.let { explicit ->
                if (Files.isRegularFile(Path.of(explicit))) return null
                return "$PROPERTY_FILE points at a missing file: $explicit"
            }

            System.getProperty(PROPERTY_PATH)?.let { explicit ->
                val directory = Path.of(explicit).toAbsolutePath()
                val candidates = listOf(directory.resolve(platform.directory), directory)
                if (candidates.any { Files.isRegularFile(it.resolve(platform.libraryFileName)) }) return null
                return "no ${platform.libraryFileName} under $PROPERTY_PATH=$directory"
            }

            val resource = "$RESOURCE_ROOT/${platform.directory}/${platform.libraryFileName}"
            if (SteamAudioNatives::class.java.getResource(resource) != null) return null
            return "no Steam Audio library on the classpath at $resource, " +
                "and neither $PROPERTY_FILE nor $PROPERTY_PATH is set"
        }

    /** True when [load] can find the library. Does not load it. */
    public val isAvailable: Boolean get() = unavailableReason == null

    /** One line for a box or a smoke test to print. */
    public fun describe(): String {
        loaded.get()?.let { return "Steam Audio loaded from $it" }
        unavailableReason?.let { return "Steam Audio unavailable: $it. $HOW_TO_FETCH" }
        return "Steam Audio available, not yet loaded"
    }

    /** The path the library was loaded from, or `null` if [load] has not run yet. */
    public val loadedFrom: Path? get() = loaded.get()

    private fun detectPlatform(): Platform {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()

        val is64BitIntel = arch == "amd64" || arch == "x86_64" || arch == "x64"
        val isArm64 = arch == "aarch64" || arch == "arm64"

        return when {
            os.contains("mac") || os.contains("darwin") -> {
                require(is64BitIntel || isArm64) { "unsupported macOS architecture: $arch" }
                Platform.MACOS
            }

            os.contains("win") -> {
                if (!is64BitIntel) {
                    throw UnsupportedOperationException(
                        "Steam Audio for Kotlin supports 64-bit Windows only (os.arch=$arch)",
                    )
                }
                Platform.WINDOWS_X64
            }

            os.contains("linux") -> {
                if (!is64BitIntel) {
                    throw UnsupportedOperationException(
                        "Steam Audio ships no Linux $arch binary upstream; " +
                            "only linux-x64 is available. Build libphonon from source, then " +
                            "point $PROPERTY_PATH or $PROPERTY_FILE at it.",
                    )
                }
                Platform.LINUX_X64
            }

            else -> throw UnsupportedOperationException("unsupported operating system: $os")
        }
    }

    private fun resolveAndLoad(): Path {
        System.getProperty(PROPERTY_FILE)?.let { explicit ->
            val path = Path.of(explicit).toAbsolutePath()
            if (!Files.isRegularFile(path)) {
                throw IOException("$PROPERTY_FILE points at a missing file: $path")
            }
            System.load(path.toString())
            return path
        }

        val platform = platform

        System.getProperty(PROPERTY_PATH)?.let { explicit ->
            val directory = Path.of(explicit).toAbsolutePath()
            // Accept either a tree with per-platform subdirectories, or a flat directory.
            val candidates = listOf(directory.resolve(platform.directory), directory)
            val root = candidates.firstOrNull { Files.isRegularFile(it.resolve(platform.libraryFileName)) }
                ?: throw IOException(
                    "could not find ${platform.libraryFileName} under $PROPERTY_PATH=$directory " +
                        "(looked in ${candidates.joinToString()})",
                )
            return loadFromDirectory(root, platform)
        }

        return loadFromDirectory(extractResources(platform), platform)
    }

    private fun loadFromDirectory(directory: Path, platform: Platform): Path {
        // Dependencies are best-effort: they only exist on some platforms, and the main
        // library may not actually import them in every build configuration.
        for (dependency in platform.dependencies) {
            val path = directory.resolve(dependency)
            if (Files.isRegularFile(path)) {
                runCatching { System.load(path.toAbsolutePath().toString()) }
            }
        }

        val library = directory.resolve(platform.libraryFileName).toAbsolutePath()
        if (!Files.isRegularFile(library)) {
            throw IOException("missing Steam Audio library: $library")
        }
        System.load(library.toString())
        return library
    }

    /**
     * Extracts this platform's libraries from classpath resources into a content-addressed
     * cache directory, and returns that directory.
     *
     * The directory name includes a digest of the extracted bytes, so a version bump lands in
     * a fresh directory rather than colliding with a stale one, and concurrent JVMs sharing a
     * cache converge on identical content.
     */
    private fun extractResources(platform: Platform): Path {
        val payloads = platform.allFiles.associateWith { name ->
            val path = "${RESOURCE_ROOT.removePrefix("/")}/${platform.directory}/$name"
            val bytes = readResource(path)
            if (bytes == null && name == platform.libraryFileName) {
                throw IOException(
                    buildString {
                        append("no Steam Audio native library found at $path.\n")
                        append("  Add the steamaudio-kotlin-natives-${platform.directory} ")
                        append("artifact to the runtime CLASSPATH.\n")
                        append("  Note: putting it on the module path does not work. It holds ")
                        append("resources and no code, so\n")
                        append("  nothing requires it, it is never resolved, and its resources ")
                        append("stay invisible. Keep the\n")
                        append("  library on the module path if you like -- just leave the ")
                        append("natives jar on the classpath.\n")
                        append("  Alternatively set $PROPERTY_PATH to a directory containing ")
                        append("the libraries, or\n")
                        append("  $PROPERTY_FILE to one library file.")
                    },
                )
            }
            bytes
        }.filterValues { it != null }.mapValues { (_, value) -> value!! }

        val digest = MessageDigest.getInstance("SHA-256").apply {
            for ((name, bytes) in payloads.toSortedMap()) {
                update(name.toByteArray())
                update(bytes)
            }
        }.digest().joinToString("") { "%02x".format(it) }.take(16)

        val cache = cacheRoot().resolve("$digest-${platform.directory}")
        Files.createDirectories(cache)

        for ((name, bytes) in payloads) {
            val target = cache.resolve(name)
            if (Files.isRegularFile(target) && Files.size(target) == bytes.size.toLong()) continue
            // Write to a unique temp file then move, so a partially written library is never
            // visible to another process reading the same cache directory.
            val temp = Files.createTempFile(cache, "$name.", ".partial")
            try {
                Files.write(temp, bytes)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        return cache
    }

    /**
     * Reads a classpath resource, trying both module-aware and class-loader lookups.
     *
     * The distinction matters once this library is used as a named module. `Class.getResource`
     * resolves against the *calling class's own module*, so when the natives ship as a separate
     * jar on the module path -- which is the whole point of publishing them separately -- the
     * library's own module does not contain them and that lookup returns null.
     *
     * The class loader does find them, because an automatic module (a plain jar with no
     * `module-info`, which is what the natives jars are) does not encapsulate its resources.
     * Both are attempted so that either layout works: natives bundled into the same jar, or
     * shipped alongside.
     */
    private fun readResource(path: String): ByteArray? {
        // Same-module lookup first: this is the case where someone has built a fat jar.
        SteamAudioNatives::class.java.getResourceAsStream("/$path")?.use { return it.readBytes() }

        val loader = SteamAudioNatives::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        loader.getResourceAsStream(path)?.use { return it.readBytes() }

        // Last resort for unusual loader arrangements, e.g. a plugin container that puts the
        // natives on the context loader but not on ours.
        Thread.currentThread().contextClassLoader
            ?.getResourceAsStream(path)
            ?.use { return it.readBytes() }

        return null
    }

    private fun cacheRoot(): Path {
        val explicit = System.getProperty("steamaudio.cache.dir")
        if (explicit != null) return Path.of(explicit)
        val base = System.getProperty("java.io.tmpdir") ?: "."
        val user = System.getProperty("user.name")?.replace(Regex("[^A-Za-z0-9_.-]"), "_") ?: "anon"
        return Path.of(base, "steamaudio-kotlin-$user")
    }
}
