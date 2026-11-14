@file:afield.runtime.completion.CompletionCategory("Graphics")
/**
 * NVIDIA's runtime CUDA C++ compiler (`libnvrtc`) over `java.lang.foreign`: CUDA C source in, PTX
 * out, for [CudaDriver.CudaContext.module].
 *
 * NVRTC ships with the CUDA toolkit, not with the driver, so a machine with only the driver has no
 * NVRTC on the loader path. [Nvrtc.loaded] therefore looks in three places, in order:
 * `FIELD_NVRTC_LIBRARY`, the loader (`libnvrtc.so`, then `.so.13`, `.so.12`), and the conda package
 * caches under `~/.local/share/{micromamba,mamba}` and `~/miniforge3`/`~/mambaforge`, where PyTorch
 * and CuPy environments leave a `cuda-nvrtc` package. Without any of them, ship PTX instead.
 */

package afield.cuda

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** One loaded NVRTC library; [compile] turns CUDA C into PTX. */
class Nvrtc private constructor(private val lookup: SymbolLookup, val library: String) {
    private val linker = Linker.nativeLinker()
    private fun h(name: String, result: MemoryLayout, vararg args: MemoryLayout) =
        linker.downcallHandle(lookup.find(name).orElseThrow { UnsatisfiedLinkError("libnvrtc lacks $name") }, FunctionDescriptor.of(result, *args))
    private val nvrtcVersion = h("nvrtcVersion", JAVA_INT, ADDRESS, ADDRESS)
    private val nvrtcGetErrorString = h("nvrtcGetErrorString", ADDRESS, JAVA_INT)
    private val nvrtcCreateProgram = h("nvrtcCreateProgram", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)
    private val nvrtcCompileProgram = h("nvrtcCompileProgram", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    private val nvrtcGetProgramLogSize = h("nvrtcGetProgramLogSize", JAVA_INT, ADDRESS, ADDRESS)
    private val nvrtcGetProgramLog = h("nvrtcGetProgramLog", JAVA_INT, ADDRESS, ADDRESS)
    private val nvrtcGetPTXSize = h("nvrtcGetPTXSize", JAVA_INT, ADDRESS, ADDRESS)
    private val nvrtcGetPTX = h("nvrtcGetPTX", JAVA_INT, ADDRESS, ADDRESS)
    private val nvrtcDestroyProgram = h("nvrtcDestroyProgram", JAVA_INT, ADDRESS)

    private fun ok(code: Any?, call: String) {
        val c = code as Int
        if (c != 0) throw IllegalStateException("$call: " + (nvrtcGetErrorString.invokeWithArguments(c) as MemorySegment).reinterpret(256).getString(0))
    }

    /** NVRTC's version as `major.minor`. */
    val version: String = Arena.ofConfined().use { a ->
        val major = a.allocate(JAVA_INT); val minor = a.allocate(JAVA_INT)
        ok(nvrtcVersion.invokeWithArguments(major, minor), "nvrtcVersion"); "${major.get(JAVA_INT, 0)}.${minor.get(JAVA_INT, 0)}"
    }

    /**
     * Compile [source] to PTX for compute capability [computeCapability] (89 for an RTX 4090).
     * A compile error throws with NVRTC's log. Name kernels `extern "C"` so
     * [CudaDriver.CudaModule.function] finds them unmangled.
     */
    fun compile(source: String, computeCapability: Int, name: String = "field.cu", options: List<String> = emptyList()): String = Arena.ofConfined().use { a ->
        val prog = a.allocate(ADDRESS)
        ok(nvrtcCreateProgram.invokeWithArguments(prog, a.allocateFrom(source), a.allocateFrom(name), 0, MemorySegment.NULL, MemorySegment.NULL), "nvrtcCreateProgram")
        val handle = prog.get(ADDRESS, 0)
        try {
            val all = listOf("--gpu-architecture=compute_$computeCapability") + options
            val opts = a.allocate(ADDRESS, all.size.toLong())
            all.forEachIndexed { i, o -> opts.setAtIndex(ADDRESS, i.toLong(), a.allocateFrom(o)) }
            val code = nvrtcCompileProgram.invokeWithArguments(handle, all.size, opts) as Int
            if (code != 0) {
                val size = a.allocate(JAVA_LONG); nvrtcGetProgramLogSize.invokeWithArguments(handle, size)
                val log = a.allocate(size.get(JAVA_LONG, 0).coerceAtLeast(1)); nvrtcGetProgramLog.invokeWithArguments(handle, log)
                throw IllegalStateException("NVRTC could not compile $name:\n${log.getString(0)}")
            }
            val size = a.allocate(JAVA_LONG); ok(nvrtcGetPTXSize.invokeWithArguments(handle, size), "nvrtcGetPTXSize")
            val ptx = a.allocate(size.get(JAVA_LONG, 0)); ok(nvrtcGetPTX.invokeWithArguments(handle, ptx), "nvrtcGetPTX")
            ptx.getString(0)
        } finally { nvrtcDestroyProgram.invokeWithArguments(prog) }
    }

    companion object {
        /** Conda package caches and environments that may hold a `cuda-nvrtc` package. */
        private fun condaCandidates(): List<Path> {
            val home = Path.of(System.getProperty("user.home"))
            val roots = listOf(".local/share/micromamba", ".local/share/mamba", "miniforge3", "mambaforge", "miniconda3", "anaconda3").map { home.resolve(it) }.filter { it.isDirectory() }
            val found = ArrayList<Path>()
            for (root in roots) Files.walk(root, 12).use { s ->
                s.filter { val n = it.name; n.startsWith("libnvrtc.so.") && !n.startsWith("libnvrtc-builtins") && !it.toString().contains("/stubs/") && Files.isRegularFile(it) }
                    .forEach { found.add(it) }
            }
            // Newest first, by the version digits after ".so.".
            fun key(p: Path) = p.name.removePrefix("libnvrtc.so.").split('.').map { it.toIntOrNull() ?: 0 }.let { v -> v.getOrElse(0) { 0 } * 1_000_000 + v.getOrElse(1) { 0 } * 1000 + v.getOrElse(2) { 0 } }
            return found.sortedByDescending(::key)
        }

        /** The first NVRTC that loads, or the places tried. */
        val loaded: Result<Nvrtc> by lazy {
            runCatching {
                check(System.getProperty("os.name").lowercase().contains("linux")) { "Field binds NVRTC on Linux only" }
                val tried = ArrayList<String>()
                fun attempt(label: String, open: () -> SymbolLookup): Nvrtc? = try { Nvrtc(open(), label) } catch (e: Throwable) { tried += "$label (${e.message})"; null }
                System.getenv("FIELD_NVRTC_LIBRARY")?.let { p -> attempt(p) { SymbolLookup.libraryLookup(Path.of(p), Arena.global()) }?.let { return@runCatching it } }
                for (n in listOf("libnvrtc.so", "libnvrtc.so.13", "libnvrtc.so.12")) attempt(n) { SymbolLookup.libraryLookup(n, Arena.global()) }?.let { return@runCatching it }
                for (p in condaCandidates()) attempt(p.toString()) { SymbolLookup.libraryLookup(p, Arena.global()) }?.let { return@runCatching it }
                throw IllegalStateException("No NVRTC found; set FIELD_NVRTC_LIBRARY or ship PTX. Tried: ${tried.joinToString("; ")}")
            }
        }
    }
}
