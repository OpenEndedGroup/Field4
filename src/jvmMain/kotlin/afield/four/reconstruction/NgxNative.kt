@file:afield.CompletionCategory("Internal")
package afield.four.reconstruction

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.nio.file.Path
import kotlin.io.path.*

/** One optional FFM library, following Manifold/Ceres. No JNI and no SDK parameter maps in Kotlin. */
internal class NgxNative private constructor(path: Path, sdk: Path) {
    private val lookup=SymbolLookup.libraryLookup(path,Arena.global())
    // The installed NGX discovery code reserves >1 MiB on its native stack. Calling it from
    // Field's small render-thread stack overflows before the JVM can report an exception.
    // One serialized platform thread gives all SDK calls a measured, explicit 8 MiB stack.
    // The render thread waits for recording to finish; GPU work remains on Field's queue.
    internal class Call(private val handle: MethodHandle) {
        fun invokeWithArguments(vararg args: Any?): Any? = try {
            worker.submit<Any?> { handle.invokeWithArguments(*args) }.get()
        } catch(e: ExecutionException) { throw e.cause ?: e }
    }
    private fun bind(name: String, result: MemoryLayout?, vararg args: MemoryLayout): Call = Call(
        Linker.nativeLinker().downcallHandle(lookup.find("f4ngx_$name").orElseThrow(),
            if(result==null)FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(result,*args)))
    val extensions=bind("extensions",JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_INT,ADDRESS,JAVA_INT)
    val open=bind("open",JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,ADDRESS)
    val optimal=bind("optimal",JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS)
    val create=bind("create",JAVA_INT,ADDRESS,JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS)
    val evaluate=bind("evaluate",JAVA_INT,ADDRESS,JAVA_LONG,ADDRESS,ADDRESS,JAVA_INT)
    val rrAvailable=bind("rr_available",JAVA_INT,ADDRESS)
    val rrOptimal=bind("rr_optimal",JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS)
    val rrCreate=bind("rr_create",JAVA_INT,ADDRESS,JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS)
    val rrEvaluate=bind("rr_evaluate",JAVA_INT,ADDRESS,JAVA_LONG,ADDRESS,ADDRESS,ADDRESS,JAVA_INT)
    val memory=bind("memory",JAVA_LONG,ADDRESS)
    val destroy=bind("destroy",null,ADDRESS)
    val close=bind("close",null,ADDRESS)
    private val error=bind("error",ADDRESS)
    init {
        check(bind("abi",JAVA_INT).invokeWithArguments()==3 && bind("image_size",JAVA_INT).invokeWithArguments()==32) {
            "Incompatible Field NGX ABI; rebuild with bash tools/lighting/build_ngx.sh"
        }
        Arena.ofShared().use { arena ->
            fun wide(text: String): MemorySegment {
                val points=text.codePoints().toArray();val data=arena.allocate((points.size+1)*4L,4)
                points.forEachIndexed { i,c -> data.set(JAVA_INT,i*4L,c) };return data
            }
            val cache=Path.of(System.getProperty("user.home"),".cache","field4","ngx").also { it.createDirectories() }
            bind("configure",null,ADDRESS,ADDRESS).invokeWithArguments(wide(sdk.resolve("lib/Linux_x86_64/rel").toString()),wide(cache.toString()))
        }
    }
    fun check(code: Any?) {
        check(code==0) { (error.invokeWithArguments() as MemorySegment).reinterpret(4096).getString(0) }
    }
    fun required(instance: Long=0, physical: Long=0,rr: Boolean=false): Set<String> = Arena.ofShared().use { arena ->
        val names=arena.allocate(4096);check(extensions.invokeWithArguments(instance,physical,if(rr)1 else 0,names,4096))
        names.getString(0).lineSequence().filter { it.isNotBlank() }.toSet()
    }
    companion object {
        private val worker=Executors.newSingleThreadExecutor { work ->
            Thread(null,work,"field-ngx",8L*1024*1024).apply { isDaemon=true }
        }
        val loaded: Result<NgxNative> by lazy {
            runCatching {
                check(System.getProperty("os.name").lowercase().contains("linux")) { "Field NGX native adapter is currently built for Linux x86_64 only" }
                check(System.getenv("FIELD_NGX_DISABLE")!="1" && !java.lang.Boolean.getBoolean("afield.ngx.disabled")) { "NGX explicitly disabled" }
                val path=Path.of(System.getenv("FIELD_NGX_LIBRARY") ?: "build/native/field-ngx/libfield_ngx.so").toAbsolutePath()
                check(path.isRegularFile()) { "Optional NGX adapter absent; build with bash tools/lighting/build_ngx.sh" }
                val sdk=Path.of(System.getenv("FIELD_DLSS_ROOT") ?: "build/lighting-experiment/upstream/DLSS").toAbsolutePath()
                check(sdk.resolve("lib/Linux_x86_64/rel").isDirectory()) { "FIELD_DLSS_ROOT must name the pinned NVIDIA/DLSS checkout" }
                NgxNative(path,sdk)
            }
        }
    }
}
