package afield.four.physics

import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.JoltPhysicsObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Getting the jolt-jni native library loaded, which has to happen once per JVM before any Jolt call.
 *
 * The library ships inside the jar as a per-platform resource, under an `os/arch` directory pair
 * followed by `com/github/stephengold/` and the platform's own filename, so there is nothing to
 * install and nothing to put on `java.library.path`. A JNI library cannot be loaded from inside a
 * jar, so the resource is copied to a temporary directory first and the copy is what gets loaded.
 * Point `-Dafield.joltjni.library` or the `AFIELD_JOLTJNI_LIBRARY` environment variable at an
 * absolute path to skip the copy and load a library you built yourself.
 *
 * Both halves of that directory pair are tables, over `os.name` and `os.arch`: macOS, Windows or
 * Linux, on aarch64 or x86-64. A platform in neither table fails here, naming the two properties it
 * read, rather than at the first native call.
 *
 * The only caller is the constructor of [JoltPhysicsWorld], which runs this on every world it
 * builds; the second and later calls return immediately.
 */

/**
 * One-time native jolt-jni bring-up (library load, cleaner, factory, allocator).
 * Called lazily from the [JoltPhysicsWorld] constructor via [ensureInitialized];
 * see JoltPhysicsWorld.kt for the physics API this supports.
 */
object JoltPhysicsBootstrap {
    private val initialized = AtomicBoolean(false)
    private val nativeLoaded = AtomicBoolean(false)

    /**
     * Loads the native library and brings Jolt up, or returns at once if that has already happened.
     *
     * Safe to call from anywhere and as often as you like: the whole body is `@Synchronized` and
     * returns on a flag, so concurrent first calls serialise and a repeat call costs an uncontended
     * monitor and nothing else. Bringing Jolt up means starting jolt-jni's cleaner thread if nothing
     * has yet, registering the default allocator, installing the default assert and trace callbacks,
     * and then creating the factory and registering Jolt's types. A factory that fails to build is
     * fatal here rather than a null somewhere later.
     *
     * A world calls this for you. Call it directly only to pay the library load somewhere you have
     * chosen, rather than on the first `JoltPhysicsWorld(...)`.
     */
    @Synchronized
    fun ensureInitialized() {
        if (initialized.get()) return

        ensureNativeLibraryLoaded()
        if (!JoltPhysicsObject.isCleanerStarted()) {
            JoltPhysicsObject.startCleaner()
        }
        Jolt.registerDefaultAllocator()
        Jolt.installDefaultAssertCallback()
        Jolt.installDefaultTraceCallback()

        val ok = Jolt.newFactory()
        check(ok) { "Jolt.newFactory() failed" }
        Jolt.registerTypes()

        initialized.set(true)
    }

    private fun ensureNativeLibraryLoaded() {
        if (nativeLoaded.get()) return

        val explicitPath = System.getProperty("afield.joltjni.library")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("AFIELD_JOLTJNI_LIBRARY")?.takeIf { it.isNotBlank() }

        if (explicitPath != null) {
            System.load(File(explicitPath).absoluteFile.path)
            nativeLoaded.set(true)
            return
        }

        val resourcePath = nativeResourcePath()
        val loader = Thread.currentThread().contextClassLoader ?: JoltPhysicsBootstrap::class.java.classLoader
        val stream = loader.getResourceAsStream(resourcePath)
            ?: JoltPhysicsBootstrap::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: error(
                "Could not find JoltJNI native library resource '$resourcePath' on the runtime classpath " +
                    "(os.name='${System.getProperty("os.name")}', os.arch='${System.getProperty("os.arch")}'). " +
                    "Set -Dafield.joltjni.library=/path/to/libjoltjni if you need to provide it manually."
            )

        val tempDir = Files.createTempDirectory("afield-joltjni-").toFile()
        tempDir.deleteOnExit()
        val tempLibrary = File(tempDir, resourcePath.substringAfterLast('/'))
        stream.use { input ->
            tempLibrary.outputStream().use { output -> input.copyTo(output) }
        }
        tempLibrary.deleteOnExit()

        try {
            System.load(tempLibrary.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "Failed to load JoltJNI native library from classpath resource '$resourcePath' " +
                    "extracted to '${tempLibrary.absolutePath}': ${e.message}"
            ).also { it.initCause(e) }
        }

        nativeLoaded.set(true)
    }

    private fun nativeResourcePath(): String {
        val osDirectory = when {
            System.getProperty("os.name").startsWith("Mac") -> "osx"
            System.getProperty("os.name").startsWith("Windows") -> "windows"
            System.getProperty("os.name").startsWith("Linux") -> "linux"
            else -> error("Unsupported OS for JoltJNI: '${System.getProperty("os.name")}'")
        }
        val archDirectory = when (System.getProperty("os.arch")) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86-64"
            else -> error("Unsupported architecture for JoltJNI: '${System.getProperty("os.arch")}'")
        }
        val libraryName = when (osDirectory) {
            "windows" -> "joltjni.dll"
            "osx" -> "libjoltjni.dylib"
            else -> "libjoltjni.so"
        }

        return "$osDirectory/$archDirectory/com/github/stephengold/$libraryName"
    }
}
