package afield.runtime.bytecode

import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * What a rewrite pass can learn about the target of a call in box code without loading its class:
 * the annotations on the method the call resolves to.
 *
 * A pass sees `INVOKEVIRTUAL afield/audio/synth/SineOsc.render(...)` as three strings. Loading
 * `SineOsc` to ask it would run static initialisers at rewrite time, on whatever thread is
 * evaluating, so [CallTargets] reads the owner's `.class` bytes as a *resource* from the snippet's
 * loader instead, parses them with `SKIP_CODE`, and walks the superclass and interfaces the same way
 * until it finds the method. Results are cached per loader and owner. JDK and Kotlin stdlib owners
 * are never read; nothing there carries Field's annotations.
 *
 * Annotations need only `BINARY` retention (they are read from class files, never by reflection).
 * Annotations are reported by descriptor, `Lafield/runtime/bytecode/StackAware;`.
 */
object CallTargets {

    /** One annotation on a method: its descriptor and its element values (enum values as their name). */
    class Annotation(val desc: String, val values: Map<String, Any?>) {
        override fun toString() = "$desc$values"
    }

    private class MethodFacts(val name: String, val desc: String, val access: Int, val annotations: List<Annotation>)

    private class ClassFacts(val superName: String?, val interfaces: List<String>, val methods: List<MethodFacts>)

    private val MISSING = ClassFacts(null, emptyList(), emptyList())

    private val byLoader = Collections.synchronizedMap(WeakHashMap<ClassLoader, ConcurrentHashMap<String, ClassFacts>>())

    private val skippedPrefixes = listOf("java/", "javax/", "jdk/", "sun/", "com/sun/", "kotlin/", "kotlinx/", "org/jetbrains/")

    /**
     * The annotations on the method a call `owner.name desc` resolves to, searching [owner] and then
     * its supertypes (so a call through a subclass, or through an interface, finds the annotation on
     * the declaration). A call to a Kotlin default-arguments stub `name$default` reports the
     * annotations of the function it stands for. Empty when nothing is annotated, the owner is a
     * JDK/stdlib class, or its bytes cannot be read.
     */
    fun annotations(loader: ClassLoader?, owner: String, name: String, desc: String): List<Annotation> {
        if (loader == null || owner.startsWith("[") || skippedPrefixes.any { owner.startsWith(it) }) return emptyList()
        if (name.endsWith("\$default")) return defaultStubAnnotations(loader, owner, name.removeSuffix("\$default"), desc)
        // Every declaration on the way up counts: an unannotated override of an annotated interface
        // method (a Signal subclass's `render`) is still the annotated API. Nearest first.
        val seen = HashSet<String>()
        val out = ArrayList<Annotation>(0)
        fun search(o: String) {
            if (!seen.add(o) || skippedPrefixes.any { o.startsWith(it) }) return
            val f = facts(loader, o)
            f.methods.firstOrNull { it.name == name && it.desc == desc }?.let { m ->
                // A private or static method is not overridden; nothing above it is the same method.
                out += m.annotations
                if (m.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC) != 0) return
            }
            f.superName?.let(::search)
            f.interfaces.forEach(::search)
        }
        search(owner)
        return out
    }

    /** The annotation with descriptor [annotationDesc] on the call's target, or null. */
    fun annotation(loader: ClassLoader?, owner: String, name: String, desc: String, annotationDesc: String): Annotation? =
        annotations(loader, owner, name, desc).firstOrNull { it.desc == annotationDesc }

    /** Internal names of [owner] and all its supertypes that could be read, nearest first. */
    fun supertypes(loader: ClassLoader?, owner: String): List<String> {
        if (loader == null) return emptyList()
        val out = LinkedHashSet<String>()
        fun walk(o: String) {
            if (!out.add(o) || skippedPrefixes.any { o.startsWith(it) }) return
            val f = facts(loader, o)
            f.superName?.let(::walk)
            f.interfaces.forEach(::walk)
        }
        walk(owner)
        return out.toList()
    }

    // `f$default(receiver?, params..., mask: Int, marker: Any?)` is a static stub with no
    // annotations of its own; its target is the `f` in the same class whose parameters are the
    // stub's minus the trailing int and Object.
    private fun defaultStubAnnotations(loader: ClassLoader, owner: String, name: String, stubDesc: String): List<Annotation> {
        val stubArgs = Type.getArgumentTypes(stubDesc)
        if (stubArgs.size < 2) return emptyList()
        val f = facts(loader, owner)
        val target = f.methods.firstOrNull { m ->
            if (m.name != name) return@firstOrNull false
            val args = Type.getArgumentTypes(m.desc)
            val static = m.access and Opcodes.ACC_STATIC != 0
            // An instance method's stub takes the receiver first.
            val stubParams = stubArgs.copyOfRange(if (static) 0 else 1, stubArgs.size - 2)
            args.contentEquals(stubParams)
        }
        return target?.annotations.orEmpty()
    }

    private fun facts(loader: ClassLoader, owner: String): ClassFacts {
        val cache = synchronized(byLoader) { byLoader.getOrPut(loader) { ConcurrentHashMap() } }
        return cache.getOrPut(owner) { read(loader, owner) ?: MISSING }
    }

    private fun read(loader: ClassLoader, owner: String): ClassFacts? {
        val bytes = try {
            loader.getResourceAsStream("$owner.class")?.use { it.readBytes() }
        } catch (_: Throwable) { null } ?: return null
        var superName: String? = null
        var interfaces: List<String> = emptyList()
        val methods = ArrayList<MethodFacts>()
        try {
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.API_VERSION) {
                override fun visit(version: Int, access: Int, name: String?, signature: String?, superName0: String?, interfaces0: Array<out String>?) {
                    superName = superName0
                    interfaces = interfaces0?.toList().orEmpty()
                }

                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    val annotations = ArrayList<Annotation>(0)
                    return object : MethodVisitor(Opcodes.API_VERSION) {
                        override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor {
                            val values = LinkedHashMap<String, Any?>()
                            annotations += Annotation(desc, values)
                            return object : AnnotationVisitor(Opcodes.API_VERSION) {
                                override fun visit(n: String?, value: Any?) { if (n != null) values[n] = value }
                                override fun visitEnum(n: String?, d: String?, value: String?) { if (n != null) values[n] = value }
                                override fun visitArray(n: String?): AnnotationVisitor {
                                    val list = ArrayList<Any?>()
                                    if (n != null) values[n] = list
                                    return object : AnnotationVisitor(Opcodes.API_VERSION) {
                                        override fun visit(n2: String?, value: Any?) { list += value }
                                        override fun visitEnum(n2: String?, d: String?, value: String?) { list += value }
                                    }
                                }
                            }
                        }

                        override fun visitEnd() {
                            methods += MethodFacts(name, descriptor, access, annotations)
                        }
                    }
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
        } catch (_: Throwable) {
            return null
        }
        return ClassFacts(superName, interfaces, methods)
    }
}
