package afield.four.texture

import afield.logger.lprintln
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

/**
 * Sends raw file descriptors to another process via SCM_RIGHTS over a Unix domain socket — the
 * mechanism [VulkanExternalMemory]'s `field-encode-linux` handoff actually needs.
 *
 * Superseded design note: the first cut tried `/proc/<pid>/fd/<fd>` duplication in the helper
 * (cheap, no native glue) — verified to work for anonymous-inode fds (pipes, eventfds, real
 * dma-bufs) but **not** for NVIDIA's `VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT` export, which
 * turned out to be backed by `/dev/nvidiactl` (a real device-special file): reopening that path
 * performs a fresh `open()` against the driver, yielding an fd that's numerically valid but
 * unrelated to the original memory object — `cuImportExternalMemory` then fails with
 * `CUDA_ERROR_UNKNOWN (999)`. SCM_RIGHTS duplicates the actual kernel `struct file*` regardless of
 * what's behind it, so it's the correct fix. Java has no standard-library `sendmsg`/ancillary-data
 * support, hence a JNI shim (`native/field-fdpass/`) rather than a pure-JVM mechanism.
 *
 * This wrapper deliberately declares **no** `external fun` itself and loads the real native class
 * (`fieldfdpass.UnixFdPassNative`, a standalone jar, not part of this Kotlin module) through a
 * fresh [URLClassLoader] via reflection, rather than a normal classpath reference. That isolation
 * is load-bearing, not style: a class with a JNI `external fun` visible on Field4's main jvmMain
 * classpath breaks the live box/REPL execution host (`NoClassDefFoundError: ReplState`, a
 * misleading error that blames a nonexistent `package` declaration) the instant *any* code path
 * references it — see the "field4-jni-repl-bug" memory note. Loading it through an isolated
 * classloader Field's own classpath scanning never walks avoids that interaction entirely.
 *
 * Linux only.
 */
object UnixFdPass {
    private val sendFdsMethod: Method? = runCatching {
        val jar = File("native/field-fdpass/build/fieldfdpass-native.jar")
        if (!jar.canRead()) {
            lprintln("error,field-stream", "fieldfdpass-native.jar not found at ${jar.path} — build it: (cd native/field-fdpass && cmake -B build && cmake --build build)")
            return@runCatching null
        }
        val lib = File("native/field-fdpass/build/libfieldfdpass.so")
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), UnixFdPass::class.java.classLoader)
        val cls = Class.forName("fieldfdpass.UnixFdPassNative", true, loader)
        // Must run from *this* classloader's own bytecode (see UnixFdPassNative.loadLibrary's
        // comment) — calling System.load() from Kotlin directly registers the library under the
        // wrong classloader and every native call fails with UnsatisfiedLinkError despite the
        // symbol being present in the process.
        cls.getMethod("loadLibrary", String::class.java).invoke(null, lib.absolutePath)
        cls.getMethod("sendFds", String::class.java, IntArray::class.java)
    }.getOrElse {
        lprintln("error,field-stream", "failed to load fieldfdpass-native.jar: ${it.message}")
        null
    }

    /**
     * Connect to the AF_UNIX SOCK_STREAM socket at [socketPath] (a peer must already be listening
     * — see `field-encode-linux`'s Linux `open` handling) and send every fd in [fds] in one
     * `sendmsg` call (one SCM_RIGHTS control message, fds preserved in array order). Returns false
     * on any failure (socket missing/not listening yet, connect refused, send failed) — callers
     * should retry with backoff for the "not listening yet" race rather than fail immediately.
     */
    fun sendFds(socketPath: String, fds: IntArray): Boolean {
        val method = sendFdsMethod ?: return false
        return runCatching { method.invoke(null, socketPath, fds) as Boolean }
            .getOrElse { lprintln("error,field-stream", "sendFds failed: ${it.message}"); false }
    }
}
