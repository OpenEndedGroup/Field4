package afield.four.physics

import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.JoltPhysicsObject
import java.util.concurrent.atomic.AtomicBoolean

object JoltPhysicsBootstrap {
    private val initialized = AtomicBoolean(false)

    @Synchronized
    fun ensureInitialized() {
        if (initialized.get()) return

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

