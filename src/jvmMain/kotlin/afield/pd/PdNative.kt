@file:afield.CompletionCategory("Synthesis")
package afield.pd

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Where libpd is, and what to do when it is not there.
 *
 * libpd is an **optional** native dependency, like Manifold, Ceres and TetGen: a clone of Field
 * builds, tests and runs without it, and only the code that reaches for [PdInstance] needs it.
 * Nothing is committed; `python3 tools/build_libpd_native.py` (or `./gradlew buildLibpdNative`)
 * builds one pinned libpd into `build/field-libpd/`.
 *
 * Resolution order, first hit wins:
 *
 *  1. `-Dafield.pd.library=<path>`
 *  2. `AFIELD_PD_LIBRARY=<path>`
 *  3. `build/field-libpd/build/libs/libpd-multi.<ext>` relative to the working directory, which is
 *     the checkout for `runJvm`, `jvmTest` and the backdoor
 *
 * It must be the *multi-instance* build (`PD_MULTI=ON`, hence `libpd-multi`): [PdInstance] gives
 * every patch owner its own Pd, and a single-instance libpd would silently share one between them.
 */
object PdLibrary {

    private val os: String get() = System.getProperty("os.name").lowercase()

    /** The platform's file name for the multi-instance libpd. */
    val fileName: String
        get() = when {
            os.contains("win") -> "libpd-multi.dll"
            os.contains("mac") || os.contains("darwin") -> "libpd-multi.dylib"
            else -> "libpd-multi.so"
        }

    /** Every path [resolvedPath] tries, in order — quoted in the "not built" error. */
    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.pd.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_PD_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) + listOf(
            Path.of("build", "field-libpd", "build", "libs", fileName).toAbsolutePath(),
            Path.of("build", "field-libpd", "build", "libs", "Release", fileName).toAbsolutePath(),
        )

    /** Not cached: building it during a live session should be picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    /** True when a libpd is where [searchedPaths] looks. */
    val isAvailable: Boolean get() = resolvedPath != null

    /** The one instruction a missing library needs. */
    val HOW_TO_BUILD: String
        get() = "Build it with `python3 tools/build_libpd_native.py` or `./gradlew buildLibpdNative` " +
            "(needs cmake, git and the network once), or point -Dafield.pd.library at a $fileName you built."

    /** One line for a box or a test to print. */
    fun describe(): String = resolvedPath?.let { "libpd at $it" }
        ?: "libpd not built. $HOW_TO_BUILD"
}

/**
 * The C ABI of `z_libpd.h`, bound with Java FFM. No wrapper library of Field's own sits between.
 *
 * Nothing here is thread-safe, and nothing here knows which Pd it is talking to: in a `PD_MULTI`
 * build the *current* instance is a thread-local inside libpd, set by [setInstance]. [PdInstance]
 * is the only caller, and it always takes its own lock and sets itself current before any other call.
 */
internal object PdNative {

    private val linker: Linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = PdLibrary.resolvedPath
            ?: throw UnsatisfiedLinkError(
                "libpd is not built. ${PdLibrary.HOW_TO_BUILD}\nLooked at: " +
                    PdLibrary.searchedPaths.joinToString(", "),
            )
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("${PdLibrary.fileName} has no symbol $name; rebuild it with tools/build_libpd_native.py")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    private fun v(vararg args: java.lang.foreign.MemoryLayout) = FunctionDescriptor.ofVoid(*args)
    private fun i(vararg args: java.lang.foreign.MemoryLayout) = FunctionDescriptor.of(JAVA_INT, *args)
    private fun p(vararg args: java.lang.foreign.MemoryLayout) = FunctionDescriptor.of(ADDRESS, *args)

    private val initH by lazy { handle("libpd_init", i()) }
    private val newInstanceH by lazy { handle("libpd_new_instance", p()) }
    private val setInstanceH by lazy { handle("libpd_set_instance", v(ADDRESS)) }
    private val freeInstanceH by lazy { handle("libpd_free_instance", v(ADDRESS)) }
    private val numInstancesH by lazy { handle("libpd_num_instances", i()) }
    private val initAudioH by lazy { handle("libpd_init_audio", i(JAVA_INT, JAVA_INT, JAVA_INT)) }
    private val blockSizeH by lazy { handle("libpd_blocksize", i()) }
    private val addToSearchPathH by lazy { handle("libpd_add_to_search_path", v(ADDRESS)) }
    private val openFileH by lazy { handle("libpd_openfile", p(ADDRESS, ADDRESS)) }
    private val closeFileH by lazy { handle("libpd_closefile", v(ADDRESS)) }
    private val dollarZeroH by lazy { handle("libpd_getdollarzero", i(ADDRESS)) }
    private val processFloatH by lazy { handle("libpd_process_float", i(JAVA_INT, ADDRESS, ADDRESS)) }
    private val bangH by lazy { handle("libpd_bang", i(ADDRESS)) }
    private val floatH by lazy { handle("libpd_float", i(ADDRESS, JAVA_FLOAT)) }
    private val symbolH by lazy { handle("libpd_symbol", i(ADDRESS, ADDRESS)) }
    private val startMessageH by lazy { handle("libpd_start_message", i(JAVA_INT)) }
    private val addFloatH by lazy { handle("libpd_add_float", v(JAVA_FLOAT)) }
    private val addSymbolH by lazy { handle("libpd_add_symbol", v(ADDRESS)) }
    private val finishListH by lazy { handle("libpd_finish_list", i(ADDRESS)) }
    private val finishMessageH by lazy { handle("libpd_finish_message", i(ADDRESS, ADDRESS)) }
    private val bindH by lazy { handle("libpd_bind", p(ADDRESS)) }
    private val unbindH by lazy { handle("libpd_unbind", v(ADDRESS)) }
    private val existsH by lazy { handle("libpd_exists", i(ADDRESS)) }
    private val arraySizeH by lazy { handle("libpd_arraysize", i(ADDRESS)) }
    private val resizeArrayH by lazy { handle("libpd_resize_array", i(ADDRESS, JAVA_LONG)) }
    private val readArrayH by lazy { handle("libpd_read_array", i(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)) }
    private val writeArrayH by lazy { handle("libpd_write_array", i(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)) }
    private val isFloatH by lazy { handle("libpd_is_float", i(ADDRESS)) }
    private val isSymbolH by lazy { handle("libpd_is_symbol", i(ADDRESS)) }
    private val getFloatH by lazy { handle("libpd_get_float", FunctionDescriptor.of(JAVA_FLOAT, ADDRESS)) }
    private val getSymbolH by lazy { handle("libpd_get_symbol", p(ADDRESS)) }
    private val nextAtomH by lazy { handle("libpd_next_atom", p(ADDRESS)) }

    private val setPrintHookH by lazy { handle("libpd_set_printhook", v(ADDRESS)) }
    private val setBangHookH by lazy { handle("libpd_set_banghook", v(ADDRESS)) }
    private val setFloatHookH by lazy { handle("libpd_set_floathook", v(ADDRESS)) }
    private val setSymbolHookH by lazy { handle("libpd_set_symbolhook", v(ADDRESS)) }
    private val setListHookH by lazy { handle("libpd_set_listhook", v(ADDRESS)) }
    private val setMessageHookH by lazy { handle("libpd_set_messagehook", v(ADDRESS)) }

    // libpd_init may be called once per process, and before any instance exists.
    private var initialised = false

    @Synchronized
    fun ensureInitialised() {
        if (initialised) return
        initH.invokeExact() as Int
        initialised = true
    }

    fun newInstance(): MemorySegment = newInstanceH.invokeExact() as MemorySegment
    fun setInstance(instance: MemorySegment) { setInstanceH.invokeExact(instance) }
    fun freeInstance(instance: MemorySegment) { freeInstanceH.invokeExact(instance) }
    fun numInstances(): Int = numInstancesH.invokeExact() as Int
    fun initAudio(inputs: Int, outputs: Int, sampleRate: Int): Int =
        initAudioH.invokeExact(inputs, outputs, sampleRate) as Int
    fun blockSize(): Int = blockSizeH.invokeExact() as Int
    fun addToSearchPath(path: MemorySegment) { addToSearchPathH.invokeExact(path) }
    fun openFile(name: MemorySegment, dir: MemorySegment): MemorySegment =
        openFileH.invokeExact(name, dir) as MemorySegment
    fun closeFile(patch: MemorySegment) { closeFileH.invokeExact(patch) }
    fun dollarZero(patch: MemorySegment): Int = dollarZeroH.invokeExact(patch) as Int
    fun processFloat(ticks: Int, input: MemorySegment, output: MemorySegment): Int =
        processFloatH.invokeExact(ticks, input, output) as Int
    fun bang(receiver: MemorySegment): Int = bangH.invokeExact(receiver) as Int
    fun float(receiver: MemorySegment, x: Float): Int = floatH.invokeExact(receiver, x) as Int
    fun symbol(receiver: MemorySegment, s: MemorySegment): Int = symbolH.invokeExact(receiver, s) as Int
    fun startMessage(max: Int): Int = startMessageH.invokeExact(max) as Int
    fun addFloat(x: Float) { addFloatH.invokeExact(x) }
    fun addSymbol(s: MemorySegment) { addSymbolH.invokeExact(s) }
    fun finishList(receiver: MemorySegment): Int = finishListH.invokeExact(receiver) as Int
    fun finishMessage(receiver: MemorySegment, selector: MemorySegment): Int =
        finishMessageH.invokeExact(receiver, selector) as Int
    fun bind(name: MemorySegment): MemorySegment = bindH.invokeExact(name) as MemorySegment
    fun unbind(binding: MemorySegment) { unbindH.invokeExact(binding) }
    fun exists(name: MemorySegment): Int = existsH.invokeExact(name) as Int
    fun arraySize(name: MemorySegment): Int = arraySizeH.invokeExact(name) as Int
    fun resizeArray(name: MemorySegment, size: Long): Int = resizeArrayH.invokeExact(name, size) as Int
    fun readArray(dest: MemorySegment, name: MemorySegment, offset: Int, n: Int): Int =
        readArrayH.invokeExact(dest, name, offset, n) as Int
    fun writeArray(name: MemorySegment, offset: Int, src: MemorySegment, n: Int): Int =
        writeArrayH.invokeExact(name, offset, src, n) as Int

    fun setPrintHook(fn: MemorySegment) { setPrintHookH.invokeExact(fn) }
    fun setBangHook(fn: MemorySegment) { setBangHookH.invokeExact(fn) }
    fun setFloatHook(fn: MemorySegment) { setFloatHookH.invokeExact(fn) }
    fun setSymbolHook(fn: MemorySegment) { setSymbolHookH.invokeExact(fn) }
    fun setListHook(fn: MemorySegment) { setListHookH.invokeExact(fn) }
    fun setMessageHook(fn: MemorySegment) { setMessageHookH.invokeExact(fn) }

    /**
     * Decode `argc` atoms starting at `argv` into Kotlin values: [Float] or [String].
     *
     * `argv` arrives as a zero-length segment from an upcall; the atoms are walked with libpd's
     * own accessors rather than by assuming `sizeof(t_atom)`, which differs by platform.
     */
    fun atoms(argc: Int, argv: MemorySegment): List<Any> {
        if (argc <= 0 || argv == MemorySegment.NULL) return emptyList()
        val out = ArrayList<Any>(argc)
        var atom = argv
        for (k in 0 until argc) {
            when {
                (isFloatH.invokeExact(atom) as Int) != 0 -> out += (getFloatH.invokeExact(atom) as Float)
                (isSymbolH.invokeExact(atom) as Int) != 0 ->
                    out += cString(getSymbolH.invokeExact(atom) as MemorySegment)
                else -> out += "?"
            }
            if (k < argc - 1) atom = nextAtomH.invokeExact(atom) as MemorySegment
        }
        return out
    }

    /** Read a NUL-terminated C string handed over by libpd. */
    fun cString(segment: MemorySegment): String =
        if (segment == MemorySegment.NULL) "" else segment.reinterpret(Long.MAX_VALUE).getString(0)

    // ---- upcalls ----------------------------------------------------------------------------

    /** What a [PdInstance] implements to receive libpd's hooks. Must never throw. */
    interface Hooks {
        fun print(s: MemorySegment)
        fun bang(recv: MemorySegment)
        fun float(recv: MemorySegment, x: Float)
        fun symbol(recv: MemorySegment, s: MemorySegment)
        fun list(recv: MemorySegment, argc: Int, argv: MemorySegment)
        fun message(recv: MemorySegment, msg: MemorySegment, argc: Int, argv: MemorySegment)
    }

    private val lookupHooks = MethodHandles.lookup()
    private val seg = MemorySegment::class.java
    private val voidT = Void.TYPE
    private val intT = Int::class.javaPrimitiveType!!
    private val floatT = Float::class.javaPrimitiveType!!

    private fun target(name: String, type: MethodType): MethodHandle =
        lookupHooks.findVirtual(Hooks::class.java, name, type)

    private val PRINT = target("print", MethodType.methodType(voidT, seg))
    private val BANG = target("bang", MethodType.methodType(voidT, seg))
    private val FLOAT = target("float", MethodType.methodType(voidT, seg, floatT))
    private val SYMBOL = target("symbol", MethodType.methodType(voidT, seg, seg))
    private val LIST = target("list", MethodType.methodType(voidT, seg, intT, seg))
    private val MESSAGE = target("message", MethodType.methodType(voidT, seg, seg, intT, seg))

    /** Install every hook for the *current* instance, as upcall stubs that live as long as [arena]. */
    fun installHooks(hooks: Hooks, arena: Arena) {
        setPrintHook(linker.upcallStub(PRINT.bindTo(hooks), v(ADDRESS), arena))
        setBangHook(linker.upcallStub(BANG.bindTo(hooks), v(ADDRESS), arena))
        setFloatHook(linker.upcallStub(FLOAT.bindTo(hooks), v(ADDRESS, JAVA_FLOAT), arena))
        setSymbolHook(linker.upcallStub(SYMBOL.bindTo(hooks), v(ADDRESS, ADDRESS), arena))
        setListHook(linker.upcallStub(LIST.bindTo(hooks), v(ADDRESS, JAVA_INT, ADDRESS), arena))
        setMessageHook(linker.upcallStub(MESSAGE.bindTo(hooks), v(ADDRESS, ADDRESS, JAVA_INT, ADDRESS), arena))
    }
}
