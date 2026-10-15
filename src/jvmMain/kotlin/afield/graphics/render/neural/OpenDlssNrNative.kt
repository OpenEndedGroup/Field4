@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.graphics.render.neural

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import kotlin.io.path.*

internal class OpenDlssNrNative(path: Path) {
    private val lookup=SymbolLookup.libraryLookup(path,Arena.global())
    private fun bind(name: String,result: MemoryLayout?,vararg args: MemoryLayout): MethodHandle =
        Linker.nativeLinker().downcallHandle(lookup.find("f4nr_$name").orElseThrow(),
            if(result==null)FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(result,*args))
    val probe=bind("probe",JAVA_INT,JAVA_LONG,JAVA_LONG,ADDRESS)
    val apply=bind("apply",JAVA_INT,ADDRESS,JAVA_LONG)
    val planClose=bind("plan_close",null,ADDRESS)
    val create=bind("create",JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT,ADDRESS,ADDRESS,ADDRESS,ADDRESS)
    val record=bind("record",JAVA_INT,ADDRESS,JAVA_LONG,ADDRESS)
    val destroy=bind("destroy",JAVA_INT,ADDRESS)
    val headBuffer=bind("head_buffer",JAVA_LONG,ADDRESS)
    val allocatedBytes=bind("allocated_bytes",JAVA_LONG,ADDRESS)
    val paddedWidth=bind("padded_width",JAVA_INT,ADDRESS)
    private val error=bind("error",ADDRESS)
    private val extensions=bind("extensions",ADDRESS)
    init {
        check(bind("abi",JAVA_INT).invokeWithArguments()==2 && bind("image_size",JAVA_INT).invokeWithArguments()==32 &&
            bind("controls_size",JAVA_INT).invokeWithArguments()==64) { "Incompatible NR adapter; run buildOpenDlssNrNative" }
    }
    fun check(code: Any?) {check(code==0) { (error.invokeWithArguments() as MemorySegment).reinterpret(4096).getString(0) }}
    fun required(): Set<String> =(extensions.invokeWithArguments() as MemorySegment).reinterpret(4096).getString(0).lineSequence().filter { it.isNotBlank() }.toSet()
    companion object {
        internal fun root(): Path = Path.of(System.getProperty("afield.opendlssnr.root", "build/native/field-opendlssnr")).toAbsolutePath()
        val loaded: Result<OpenDlssNrNative> by lazy { runCatching {
            check(System.getProperty("os.name").lowercase().contains("linux") && System.getProperty("os.arch") in setOf("amd64","x86_64")) { "NR adapter supports Linux x86_64" }
            check(System.getenv("FIELD_OPEN_DLSS_NR_DISABLE")!="1" && !java.lang.Boolean.getBoolean("afield.opendlssnr.disabled")) { "NR explicitly disabled" }
            val path=Path.of(System.getenv("FIELD_OPEN_DLSS_NR_LIBRARY") ?: root().resolve("libfield_opendlssnr.so").toString())
            check(path.isRegularFile()) { "Optional NR adapter absent; run ./gradlew buildOpenDlssNr" }
            OpenDlssNrNative(path)
        } }
    }
}
