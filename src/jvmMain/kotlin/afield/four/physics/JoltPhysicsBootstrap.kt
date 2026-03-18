package afield.four.physics

import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.JoltPhysicsObject
import electrostatic4j.snaploader.LibraryInfo
import electrostatic4j.snaploader.LoadingCriterion
import electrostatic4j.snaploader.NativeBinaryLoader
import electrostatic4j.snaploader.filesystem.DirectoryPath
import electrostatic4j.snaploader.platform.NativeDynamicLibrary
import electrostatic4j.snaploader.platform.util.PlatformPredicate
import java.util.concurrent.atomic.AtomicBoolean

object JoltPhysicsBootstrap {
    private val initialized = AtomicBoolean(false)

    @Synchronized
    fun ensureInitialized() {
        if (initialized.get()) return

        val info = LibraryInfo(null, "joltjni", DirectoryPath.USER_DIR)
        val loader = NativeBinaryLoader(info)

        val libraries = arrayOf(
            NativeDynamicLibrary("linux/aarch64/com/github/stephengold", PlatformPredicate.LINUX_ARM_64),
            NativeDynamicLibrary("linux/armhf/com/github/stephengold", PlatformPredicate.LINUX_ARM_32),
            NativeDynamicLibrary("linux/x86-64/com/github/stephengold", PlatformPredicate.LINUX_X86_64),
            NativeDynamicLibrary("osx/aarch64/com/github/stephengold", PlatformPredicate.MACOS_ARM_64),
            NativeDynamicLibrary("osx/x86-64/com/github/stephengold", PlatformPredicate.MACOS_X86_64),
            NativeDynamicLibrary("windows/x86-64/com/github/stephengold", PlatformPredicate.WIN_X86_64)
        )

        loader.registerNativeLibraries(libraries).initPlatformLibrary()
        try {
            loader.loadLibrary(LoadingCriterion.CLEAN_EXTRACTION)
        } catch (exception: Exception) {
            throw IllegalStateException("Failed to load a Jolt-JNI native library.", exception)
        }

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
}

