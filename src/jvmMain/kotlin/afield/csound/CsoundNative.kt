package afield.csound

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Where libcsound is, and what to do when it is not there.
 *
 * Csound is an **optional** native dependency, like libpd and LuaJIT: a clone of Field builds, tests
 * and runs without it, and only code that reaches for [CsoundInstance] needs it. Nothing is
 * committed; `python3 tools/build_csound_native.py` (or `./gradlew buildCsoundNative`) builds one
 * pinned Csound into `build/field-csound/`.
 *
 * Resolution order, first hit wins:
 *
 *  1. `-Dafield.csound.library=<path>`
 *  2. `AFIELD_CSOUND_LIBRARY=<path>`
 *  3. `build/field-csound/build/…` relative to the working directory (the checkout, for `runJvm`,
 *     `jvmTest` and the backdoor)
 *  4. an installed Csound: `/Library/Frameworks/CsoundLib64.framework` on a Mac, `libcsound64.so.6.0`
 *     in the usual Linux library directories
 *
 * It must be a double-precision Csound (`MYFLT` = `double`), which every official release is.
 */
object CsoundLibrary {

    private val os: String get() = System.getProperty("os.name").lowercase()
    private val mac: Boolean get() = os.contains("mac") || os.contains("darwin")

    /** Every path [resolvedPath] tries, in order — quoted in the "not built" error. */
    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.csound.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_CSOUND_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) + when {
            mac -> listOf(
                Path.of("build", "field-csound", "build", "CsoundLib64.framework", "CsoundLib64").toAbsolutePath(),
                Path.of("/Library/Frameworks/CsoundLib64.framework/CsoundLib64"),
            )
            os.contains("win") -> listOf(Path.of("C:/Program Files/Csound6_x64/bin/csound64.dll"))
            else -> listOf(Path.of("build", "field-csound", "build", "libcsound64.so").toAbsolutePath()) +
                listOf("/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/local/lib")
                    .map { Path.of(it, "libcsound64.so.6.0") }
        }

    /** Not cached: building it during a live session is picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    /** True when a libcsound is where [searchedPaths] looks. */
    val isAvailable: Boolean get() = resolvedPath != null

    /** The one instruction a missing library needs. */
    val HOW_TO_BUILD: String
        get() = "Build it with `python3 tools/build_csound_native.py` or `./gradlew buildCsoundNative` " +
            "(needs cmake, flex, bison, libsndfile and the network once), or point -Dafield.csound.library / " +
            "AFIELD_CSOUND_LIBRARY at a libcsound you have."

    /** One line for a box or a test to print. */
    fun describe(): String = resolvedPath?.let { "Csound at $it" } ?: "Csound not built. $HOW_TO_BUILD"
}

/**
 * The part of `csound.h` Field uses, bound with Java FFM. Downcalls only: Csound's messages are
 * collected in its own message buffer and read by [CsoundInstance.poll], so no upcall ever runs on
 * the audio thread.
 *
 * Nothing here is thread-safe; [CsoundInstance] holds its lock around every call except the channel
 * accessors, which Csound itself guards.
 */
internal object CsoundNative {

    private val linker: Linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = CsoundLibrary.resolvedPath
            ?: throw UnsatisfiedLinkError(
                "Csound is not built. ${CsoundLibrary.HOW_TO_BUILD}\nLooked at: " +
                    CsoundLibrary.searchedPaths.joinToString(", "),
            )
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("libcsound has no symbol $name; is it Csound 6.14 or later?")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    private fun v(vararg args: MemoryLayout) = FunctionDescriptor.ofVoid(*args)
    private fun i(vararg args: MemoryLayout) = FunctionDescriptor.of(JAVA_INT, *args)
    private fun p(vararg args: MemoryLayout) = FunctionDescriptor.of(ADDRESS, *args)
    private fun d(vararg args: MemoryLayout) = FunctionDescriptor.of(JAVA_DOUBLE, *args)

    private val initializeH by lazy { handle("csoundInitialize", i(JAVA_INT)) }
    private val setGlobalEnvH by lazy { handle("csoundSetGlobalEnv", i(ADDRESS, ADDRESS)) }
    private val createH by lazy { handle("csoundCreate", p(ADDRESS)) }
    private val destroyH by lazy { handle("csoundDestroy", v(ADDRESS)) }
    private val getVersionH by lazy { handle("csoundGetVersion", i()) }
    private val setOptionH by lazy { handle("csoundSetOption", i(ADDRESS, ADDRESS)) }
    private val compileOrcH by lazy { handle("csoundCompileOrc", i(ADDRESS, ADDRESS)) }
    private val readScoreH by lazy { handle("csoundReadScore", i(ADDRESS, ADDRESS)) }
    private val inputMessageH by lazy { handle("csoundInputMessage", v(ADDRESS, ADDRESS)) }
    private val startH by lazy { handle("csoundStart", i(ADDRESS)) }
    private val performKsmpsH by lazy { handle("csoundPerformKsmps", i(ADDRESS)) }
    private val cleanupH by lazy { handle("csoundCleanup", i(ADDRESS)) }
    private val getSpoutH by lazy { handle("csoundGetSpout", p(ADDRESS)) }
    private val getSpinH by lazy { handle("csoundGetSpin", p(ADDRESS)) }
    private val getKsmpsH by lazy { handle("csoundGetKsmps", i(ADDRESS)) }
    private val getNchnlsH by lazy { handle("csoundGetNchnls", i(ADDRESS)) }
    private val getNchnlsInputH by lazy { handle("csoundGetNchnlsInput", i(ADDRESS)) }
    private val getSrH by lazy { handle("csoundGetSr", d(ADDRESS)) }
    private val get0dBFSH by lazy { handle("csoundGet0dBFS", d(ADDRESS)) }
    private val getScoreTimeH by lazy { handle("csoundGetScoreTime", d(ADDRESS)) }
    private val setControlChannelH by lazy { handle("csoundSetControlChannel", v(ADDRESS, ADDRESS, JAVA_DOUBLE)) }
    private val getControlChannelH by lazy { handle("csoundGetControlChannel", d(ADDRESS, ADDRESS, ADDRESS)) }
    private val createMessageBufferH by lazy { handle("csoundCreateMessageBuffer", v(ADDRESS, JAVA_INT)) }
    private val getFirstMessageH by lazy { handle("csoundGetFirstMessage", p(ADDRESS)) }
    private val getFirstMessageAttrH by lazy { handle("csoundGetFirstMessageAttr", i(ADDRESS)) }
    private val popFirstMessageH by lazy { handle("csoundPopFirstMessage", v(ADDRESS)) }
    private val getMessageCntH by lazy { handle("csoundGetMessageCnt", i(ADDRESS)) }
    private val destroyMessageBufferH by lazy { handle("csoundDestroyMessageBuffer", v(ADDRESS)) }
    private val getTableH by lazy { handle("csoundGetTable", i(ADDRESS, ADDRESS, JAVA_INT)) }

    private var initialised = false

    /**
     * Once per process: no signal handlers and no atexit hook (the JVM owns both), and an empty
     * plugin directory, so an installed Csound's plugin opcodes are not loaded into this one.
     */
    @Synchronized
    fun ensureInitialised() {
        if (initialised) return
        // CSOUNDINIT_NO_SIGNAL_HANDLER | CSOUNDINIT_NO_ATEXIT
        initializeH.invokeExact(3) as Int
        val empty = java.nio.file.Files.createTempDirectory("field-csound-plugins-").toFile().apply { deleteOnExit() }
        Arena.ofConfined().use { a ->
            setGlobalEnvH.invokeExact(a.allocateFrom("OPCODE6DIR64"), a.allocateFrom(empty.absolutePath)) as Int
        }
        initialised = true
    }

    fun create(): MemorySegment = createH.invokeExact(MemorySegment.NULL) as MemorySegment
    fun destroy(cs: MemorySegment) { destroyH.invokeExact(cs) }
    fun version(): Int = getVersionH.invokeExact() as Int
    fun setOption(cs: MemorySegment, option: MemorySegment): Int = setOptionH.invokeExact(cs, option) as Int
    fun compileOrc(cs: MemorySegment, orc: MemorySegment): Int = compileOrcH.invokeExact(cs, orc) as Int
    fun readScore(cs: MemorySegment, sco: MemorySegment): Int = readScoreH.invokeExact(cs, sco) as Int
    fun inputMessage(cs: MemorySegment, line: MemorySegment) { inputMessageH.invokeExact(cs, line) }
    fun start(cs: MemorySegment): Int = startH.invokeExact(cs) as Int
    fun performKsmps(cs: MemorySegment): Int = performKsmpsH.invokeExact(cs) as Int
    fun cleanup(cs: MemorySegment): Int = cleanupH.invokeExact(cs) as Int
    fun spout(cs: MemorySegment): MemorySegment = getSpoutH.invokeExact(cs) as MemorySegment
    fun spin(cs: MemorySegment): MemorySegment = getSpinH.invokeExact(cs) as MemorySegment
    fun ksmps(cs: MemorySegment): Int = getKsmpsH.invokeExact(cs) as Int
    fun nchnls(cs: MemorySegment): Int = getNchnlsH.invokeExact(cs) as Int
    fun nchnlsInput(cs: MemorySegment): Int = getNchnlsInputH.invokeExact(cs) as Int
    fun sr(cs: MemorySegment): Double = getSrH.invokeExact(cs) as Double
    fun zeroDbfs(cs: MemorySegment): Double = get0dBFSH.invokeExact(cs) as Double
    fun scoreTime(cs: MemorySegment): Double = getScoreTimeH.invokeExact(cs) as Double
    fun setControlChannel(cs: MemorySegment, name: MemorySegment, value: Double) {
        setControlChannelH.invokeExact(cs, name, value)
    }
    fun getControlChannel(cs: MemorySegment, name: MemorySegment, err: MemorySegment): Double =
        getControlChannelH.invokeExact(cs, name, err) as Double
    fun createMessageBuffer(cs: MemorySegment) { createMessageBufferH.invokeExact(cs, 0) }
    fun firstMessage(cs: MemorySegment): MemorySegment = getFirstMessageH.invokeExact(cs) as MemorySegment
    fun firstMessageAttr(cs: MemorySegment): Int = getFirstMessageAttrH.invokeExact(cs) as Int
    fun popFirstMessage(cs: MemorySegment) { popFirstMessageH.invokeExact(cs) }
    fun messageCount(cs: MemorySegment): Int = getMessageCntH.invokeExact(cs) as Int
    fun destroyMessageBuffer(cs: MemorySegment) { destroyMessageBufferH.invokeExact(cs) }
    fun getTable(cs: MemorySegment, out: MemorySegment, number: Int): Int = getTableH.invokeExact(cs, out, number) as Int

    fun cString(s: MemorySegment): String =
        if (s == MemorySegment.NULL) "" else s.reinterpret(Long.MAX_VALUE).getString(0)
}
