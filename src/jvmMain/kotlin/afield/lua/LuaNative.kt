package afield.lua

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Where LuaJIT is, and what to do when it is not there.
 *
 * LuaJIT is an **optional** native dependency, like libpd, Manifold and Ceres: a clone of Field builds,
 * tests and runs without it, and only a Lua box needs it. Nothing is committed and nothing is built:
 * `brew install luajit` on a Mac, `apt install libluajit-5.1-2` (or `-dev`) on Debian and Ubuntu.
 *
 * Resolution order, first hit wins:
 *
 *  1. `-Dafield.lua.library=<path>`
 *  2. `AFIELD_LUA_LIBRARY=<path>`
 *  3. Homebrew on Apple silicon and Intel (`/opt/homebrew/lib`, `/usr/local/lib`), then the usual Linux
 *     library directories
 *
 * Any library exporting the Lua 5.1 C API would load, but the language is written against LuaJIT 2.1
 * and says so: [LuaState.version] reports `jit.version`, and the tests assert it.
 */
object LuaLibrary {

    private val os: String get() = System.getProperty("os.name").lowercase()

    /** The platform's file names for LuaJIT, most specific first. */
    val fileNames: List<String>
        get() = when {
            os.contains("win") -> listOf("lua51.dll", "luajit.dll")
            os.contains("mac") || os.contains("darwin") -> listOf("libluajit-5.1.dylib", "libluajit-5.1.2.dylib")
            else -> listOf("libluajit-5.1.so.2", "libluajit-5.1.so")
        }

    private val directories: List<String>
        get() = when {
            os.contains("mac") || os.contains("darwin") -> listOf("/opt/homebrew/lib", "/usr/local/lib", "/opt/local/lib")
            os.contains("win") -> emptyList()
            else -> listOf("/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/local/lib")
        }

    /** Every path [resolvedPath] tries, in order — quoted in the "not installed" error. */
    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.lua.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_LUA_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) + directories.flatMap { dir -> fileNames.map { Path.of(dir, it) } }

    /** Not cached: installing LuaJIT during a live session is picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    /** True when a LuaJIT is where [searchedPaths] looks. */
    val isAvailable: Boolean get() = resolvedPath != null

    /** The one instruction a missing library needs. */
    val HOW_TO_INSTALL: String
        get() = when {
            os.contains("mac") || os.contains("darwin") -> "Install it with `brew install luajit`"
            os.contains("win") -> "Put a LuaJIT lua51.dll somewhere and point AFIELD_LUA_LIBRARY at it"
            else -> "Install it with `apt install libluajit-5.1-2` (or your distribution's luajit package)"
        } + ", or point -Dafield.lua.library / AFIELD_LUA_LIBRARY at a LuaJIT library."

    /** One line for a box or a test to print. */
    fun describe(): String = resolvedPath?.let { "LuaJIT at $it" } ?: "LuaJIT not found. $HOW_TO_INSTALL"
}

/**
 * The Lua 5.1 C API that LuaJIT exports, bound with Java FFM, and the one upcall through which Lua calls
 * Kotlin. No wrapper library sits between.
 *
 * Several things in `lua.h` are macros, not functions, and have no symbol to bind: `lua_pop` is
 * `lua_settop(L, -n-1)`, `lua_getglobal` is `lua_getfield(L, LUA_GLOBALSINDEX, k)`, `lua_upvalueindex(i)`
 * is `LUA_GLOBALSINDEX - i`, `luaL_dostring` is a load and a pcall. Those are written out in [LuaState].
 *
 * Nothing here is thread-safe; [LuaState] holds its own lock around every call.
 */
internal object LuaNative {

    const val LUA_REGISTRYINDEX = -10000
    const val LUA_GLOBALSINDEX = -10002
    const val LUA_MULTRET = -1

    const val LUA_OK = 0
    const val LUA_YIELD = 1
    const val LUA_ERRRUN = 2
    const val LUA_ERRSYNTAX = 3
    const val LUA_ERRMEM = 4
    const val LUA_ERRERR = 5

    const val LUA_TNONE = -1
    const val LUA_TNIL = 0
    const val LUA_TBOOLEAN = 1
    const val LUA_TLIGHTUSERDATA = 2
    const val LUA_TNUMBER = 3
    const val LUA_TSTRING = 4
    const val LUA_TTABLE = 5
    const val LUA_TFUNCTION = 6
    const val LUA_TUSERDATA = 7
    const val LUA_TTHREAD = 8

    const val LUA_GCCOUNT = 3
    const val LUA_GCCOUNTB = 4

    /** `lua_upvalueindex(i)`. */
    fun upvalueIndex(i: Int) = LUA_GLOBALSINDEX - i

    private val linker: Linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = LuaLibrary.resolvedPath
            ?: throw UnsatisfiedLinkError(
                "LuaJIT is not installed. ${LuaLibrary.HOW_TO_INSTALL}\nLooked at: " +
                    LuaLibrary.searchedPaths.joinToString(", "),
            )
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("${LuaLibrary.resolvedPath} has no symbol $name; is it LuaJIT 2.1 (the Lua 5.1 C API)?")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    private fun v(vararg args: MemoryLayout) = FunctionDescriptor.ofVoid(*args)
    private fun i(vararg args: MemoryLayout) = FunctionDescriptor.of(JAVA_INT, *args)
    private fun p(vararg args: MemoryLayout) = FunctionDescriptor.of(ADDRESS, *args)

    private val newStateH by lazy { handle("luaL_newstate", p()) }
    private val openLibsH by lazy { handle("luaL_openlibs", v(ADDRESS)) }
    private val closeH by lazy { handle("lua_close", v(ADDRESS)) }
    private val loadBufferH by lazy { handle("luaL_loadbuffer", i(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)) }
    private val pcallH by lazy { handle("lua_pcall", i(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)) }
    private val getTopH by lazy { handle("lua_gettop", i(ADDRESS)) }
    private val setTopH by lazy { handle("lua_settop", v(ADDRESS, JAVA_INT)) }
    private val typeH by lazy { handle("lua_type", i(ADDRESS, JAVA_INT)) }
    private val toLStringH by lazy { handle("lua_tolstring", p(ADDRESS, JAVA_INT, ADDRESS)) }
    private val toNumberH by lazy { handle("lua_tonumber", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_INT)) }
    private val toBooleanH by lazy { handle("lua_toboolean", i(ADDRESS, JAVA_INT)) }
    private val toPointerH by lazy { handle("lua_topointer", p(ADDRESS, JAVA_INT)) }
    private val pushNumberH by lazy { handle("lua_pushnumber", v(ADDRESS, JAVA_DOUBLE)) }
    private val pushLStringH by lazy { handle("lua_pushlstring", v(ADDRESS, ADDRESS, JAVA_LONG)) }
    private val pushBooleanH by lazy { handle("lua_pushboolean", v(ADDRESS, JAVA_INT)) }
    private val pushNilH by lazy { handle("lua_pushnil", v(ADDRESS)) }
    private val pushValueH by lazy { handle("lua_pushvalue", v(ADDRESS, JAVA_INT)) }
    private val pushCClosureH by lazy { handle("lua_pushcclosure", v(ADDRESS, ADDRESS, JAVA_INT)) }
    private val createTableH by lazy { handle("lua_createtable", v(ADDRESS, JAVA_INT, JAVA_INT)) }
    private val rawGetH by lazy { handle("lua_rawget", v(ADDRESS, JAVA_INT)) }
    private val rawSetH by lazy { handle("lua_rawset", v(ADDRESS, JAVA_INT)) }
    private val rawGetIH by lazy { handle("lua_rawgeti", v(ADDRESS, JAVA_INT, JAVA_INT)) }
    private val rawSetIH by lazy { handle("lua_rawseti", v(ADDRESS, JAVA_INT, JAVA_INT)) }
    private val nextH by lazy { handle("lua_next", i(ADDRESS, JAVA_INT)) }
    private val objLenH by lazy { handle("lua_objlen", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT)) }
    private val insertH by lazy { handle("lua_insert", v(ADDRESS, JAVA_INT)) }
    private val removeH by lazy { handle("lua_remove", v(ADDRESS, JAVA_INT)) }
    private val refH by lazy { handle("luaL_ref", i(ADDRESS, JAVA_INT)) }
    private val unrefH by lazy { handle("luaL_unref", v(ADDRESS, JAVA_INT, JAVA_INT)) }
    private val gcH by lazy { handle("lua_gc", i(ADDRESS, JAVA_INT, JAVA_INT)) }
    private val getMetatableH by lazy { handle("lua_getmetatable", i(ADDRESS, JAVA_INT)) }

    /**
     * The one C function every Kotlin function Lua can call goes through: `int dispatch(lua_State*)`,
     * pushed as a closure whose first upvalue is the function's number in [LuaState]'s handler table.
     * One stub for the life of the process, so pushing a new Kotlin function costs a table entry, not
     * an upcall stub.
     */
    val dispatchStub: MemorySegment by lazy {
        val target = MethodHandles.lookup().findStatic(
            LuaState::class.java, "dispatch", MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
        )
        linker.upcallStub(target, i(ADDRESS), Arena.global())
    }

    fun newState(): MemorySegment = newStateH.invokeExact() as MemorySegment
    fun openLibs(l: MemorySegment) { openLibsH.invokeExact(l) }
    fun close(l: MemorySegment) { closeH.invokeExact(l) }
    fun loadBuffer(l: MemorySegment, buf: MemorySegment, size: Long, name: MemorySegment): Int =
        loadBufferH.invokeExact(l, buf, size, name) as Int
    fun pcall(l: MemorySegment, nargs: Int, nresults: Int, errfunc: Int): Int =
        pcallH.invokeExact(l, nargs, nresults, errfunc) as Int
    fun getTop(l: MemorySegment): Int = getTopH.invokeExact(l) as Int
    fun setTop(l: MemorySegment, idx: Int) { setTopH.invokeExact(l, idx) }
    fun pop(l: MemorySegment, n: Int) = setTop(l, -n - 1)
    fun type(l: MemorySegment, idx: Int): Int = typeH.invokeExact(l, idx) as Int
    fun toLString(l: MemorySegment, idx: Int, len: MemorySegment): MemorySegment =
        toLStringH.invokeExact(l, idx, len) as MemorySegment
    fun toNumber(l: MemorySegment, idx: Int): Double = toNumberH.invokeExact(l, idx) as Double
    fun toBoolean(l: MemorySegment, idx: Int): Boolean = (toBooleanH.invokeExact(l, idx) as Int) != 0
    fun toPointer(l: MemorySegment, idx: Int): Long = (toPointerH.invokeExact(l, idx) as MemorySegment).address()
    fun pushNumber(l: MemorySegment, x: Double) { pushNumberH.invokeExact(l, x) }
    fun pushLString(l: MemorySegment, s: MemorySegment, len: Long) { pushLStringH.invokeExact(l, s, len) }
    fun pushBoolean(l: MemorySegment, b: Boolean) { pushBooleanH.invokeExact(l, if (b) 1 else 0) }
    fun pushNil(l: MemorySegment) { pushNilH.invokeExact(l) }
    fun pushValue(l: MemorySegment, idx: Int) { pushValueH.invokeExact(l, idx) }
    fun pushCClosure(l: MemorySegment, fn: MemorySegment, n: Int) { pushCClosureH.invokeExact(l, fn, n) }
    fun createTable(l: MemorySegment, narr: Int, nrec: Int) { createTableH.invokeExact(l, narr, nrec) }
    fun rawGet(l: MemorySegment, idx: Int) { rawGetH.invokeExact(l, idx) }
    fun rawSet(l: MemorySegment, idx: Int) { rawSetH.invokeExact(l, idx) }
    fun rawGetI(l: MemorySegment, idx: Int, n: Int) { rawGetIH.invokeExact(l, idx, n) }
    fun rawSetI(l: MemorySegment, idx: Int, n: Int) { rawSetIH.invokeExact(l, idx, n) }
    fun next(l: MemorySegment, idx: Int): Int = nextH.invokeExact(l, idx) as Int
    fun objLen(l: MemorySegment, idx: Int): Long = objLenH.invokeExact(l, idx) as Long
    fun insert(l: MemorySegment, idx: Int) { insertH.invokeExact(l, idx) }
    fun remove(l: MemorySegment, idx: Int) { removeH.invokeExact(l, idx) }
    fun ref(l: MemorySegment, t: Int): Int = refH.invokeExact(l, t) as Int
    fun unref(l: MemorySegment, t: Int, ref: Int) { unrefH.invokeExact(l, t, ref) }
    fun gc(l: MemorySegment, what: Int, data: Int): Int = gcH.invokeExact(l, what, data) as Int
    /** Pushes the metatable of the value at [idx] and returns true, or pushes nothing and returns false. */
    fun getMetatable(l: MemorySegment, idx: Int): Boolean = (getMetatableH.invokeExact(l, idx) as Int) != 0
}
