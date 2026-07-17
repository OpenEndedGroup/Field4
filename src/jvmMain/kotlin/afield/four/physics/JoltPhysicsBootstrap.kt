package afield.four.physics

import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.JoltPhysicsObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time native jolt-jni bring-up (library load, cleaner, factory, allocator).
 * Called lazily from the [JoltPhysicsWorld] constructor via [ensureInitialized];
 * see JoltPhysicsWorld.kt for the physics API this supports.
 */
object JoltPhysicsBootstrap {
    private val initialized = AtomicBoolean(false)
    private val nativeLoaded = AtomicBoolean(false)

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
