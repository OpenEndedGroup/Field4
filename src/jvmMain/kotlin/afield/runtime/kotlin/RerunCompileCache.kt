package afield.runtime.kotlin

import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.util.LinkedSnippet

/**
 * Compile once, re-evaluate on rerun: one K2 engine's record of the snippets it has compiled, so a
 * box that reruns with exactly the same expanded source (a `propDown` reader re-launched by its
 * writer, the regathered preamble in front of it) can evaluate the snippet it already has instead
 * of compiling it again.
 *
 * Measured on Kotlin 2.5.0-Beta1's `K2ReplEvaluator` (`afield.K2ReevalProbe`, 2026-10-14), re-evaluating an old
 * `LinkedSnippet` is NOT the same as recompiling its source in two ways, and the cache refuses both:
 *
 *  1. **The body does not run** when the snippet is re-evaluated straight after its own previous
 *     evaluation: the evaluator reuses the class it already loaded, and a K2 snippet's body runs
 *     once per loaded class. (`lst.add(1); lst.size` gave 1 twice and the list kept one element.)
 *     With any other snippet evaluated in between the class is reloaded and the body runs. So a
 *     hit is refused when the cached snippet is the evaluator's last one ([KotlinInterface2] checks).
 *  2. **Stale bindings.** A snippet binds each name it uses to the snippet *class* that declared it
 *     when it was compiled. If a later snippet redeclares that name, a recompile binds the new
 *     declaration and a re-evaluation keeps the old one (`q * 2` stayed 10 after `val q = 6`). So
 *     every entry records which earlier snippet classes it references, and is refused once any
 *     name those classes declare has been redeclared by a later compile.
 *
 * Mutable declarations and their readers also compile afresh: cached evaluation can reload historical backing
 * classes, leaving captured mutable slots outside the evaluator's current history.
 *
 * Also refused, conservatively: sources that carry `@file:` annotations (`DependsOn` refines the
 * compile configuration) — and a compiled `import` invalidates every entry, since imports may
 * change how already-compiled names would resolve now.
 *
 * Everything here runs under the engine's compile mutex.
 */
internal class RerunCompileCache(private val maxEntries: Int = 64) {

    class Entry(
        val snippet: LinkedSnippet<CompiledScript>,
        val referencedSnippetClasses: Set<String>,
        val importGeneration: Int,
        val requiresFreshEvaluation: Boolean,
    )

    /** Snippet class internal name -> names that snippet declares. */
    private val declaredBy = HashMap<String, Set<String>>()

    /** Declared name -> internal name of the latest snippet class that declared it. */
    private val latestDeclarer = HashMap<String, String>()

    /** Snippet class internal names in compile order. */
    private val snippetClasses = ArrayList<String>()

    /** Mutable declarations and snippets that depend on them cannot fork their backing classes. */
    private val statefulSnippetClasses = HashSet<String>()

    private var importGeneration = 0

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    var hits = 0
        private set
    var misses = 0
        private set

    /** Why the last [lookup] missed, for the log. */
    var lastMissReason: String = ""
        private set

    fun cacheable(source: String): Boolean = !source.contains("@file:")

    fun lookup(key: String): Entry? {
        val e = entries[key]
        val reason = when {
            e == null -> "not compiled before"
            e.requiresFreshEvaluation -> "mutable bindings need their existing backing classes"
            e.importGeneration != importGeneration -> "an import was compiled since"
            else -> e.referencedSnippetClasses.firstNotNullOfOrNull { r ->
                declaredBy[r]?.firstOrNull { latestDeclarer[it] != r }?.let { "'$it' was redeclared since" }
            }
        }
        if (reason != null) {
            if (e != null) entries.remove(key)
            lastMissReason = reason
            misses++
            return null
        }
        hits++
        return e
    }

    /** Count a hit [KotlinInterface2] refused after [lookup] (see point 1 above) as a miss. */
    fun refuse(key: String, reason: String) {
        entries.remove(key)
        hits--
        misses++
        lastMissReason = reason
    }

    /** Register a snippet that was really compiled, and cache it under [key] when non-null. */
    fun recordCompile(key: String?, source: String, snippet: LinkedSnippet<CompiledScript>) {
        val script = snippet.get() as? KJvmCompiledScript
        val files = (script?.getCompiledModule() as? KJvmCompiledModuleInMemory)?.compilerOutputFiles
        if (script == null || files == null) {
            // Unknown shape: nothing can be proven about it, so it may have shadowed anything.
            importGeneration++
            return
        }
        val own = script.scriptClassFQName.replace('.', '/')
        val classFiles = files.filterKeys { it.endsWith(".class") }

        // References are taken before this snippet's own declarations are registered.
        val text = classFiles.values.joinToString("\u0000") { String(it, Charsets.ISO_8859_1) }
        val refs = snippetClasses.filterTo(HashSet()) { it != own && text.contains(it) }

        val declared = HashSet<String>()
        classFiles.forEach { (path, bytes) ->
            val internal = path.removeSuffix(".class")
            if (internal == own) declared += declaredNames(bytes)
            else if (internal.startsWith("$own$")) declared += internal.substringAfterLast('$')
        }
        declaredBy[own] = declared
        declared.forEach { latestDeclarer[it] = own }
        snippetClasses += own

        if (source.lineSequence().any { it.trimStart().startsWith("import ") }) importGeneration++
        val mutableState = declaresMutableState(source)
        val requiresFreshEvaluation = mutableState || refs.any { it in statefulSnippetClasses }
        if (requiresFreshEvaluation) statefulSnippetClasses += own
        if (key != null) entries[key] = Entry(snippet, refs, importGeneration, requiresFreshEvaluation)
    }

    // Re-evaluating a cached declaration can clone historical classes in a new loader. Closures
    // then point at slots absent from the current REPL history. Fresh compilation preserves the
    // existing classes, so a declaration reset can reach every older captured mutable slot.
    private fun declaresMutableState(source: String): Boolean = try {
        val statements = FieldKtParser.parse(source).script?.blockExpression?.statements
        statements == null || statements.filterIsInstance<KtProperty>().any { it.isVar }
    } catch (_: Exception) {
        // Unknown source shape: preserve evaluation semantics by compiling afresh.
        true
    }

    private fun declaredNames(bytes: ByteArray): Set<String> {
        val names = HashSet<String>()
        fun add(n: String) {
            if (n.startsWith("<") || n.startsWith("$$") || n == "INSTANCE") return
            names += n
            for (p in listOf("get", "set", "is")) {
                if (n.length > p.length && n.startsWith(p) && n[p.length].isUpperCase()) {
                    names += n.substring(p.length).replaceFirstChar { it.lowercase() }
                }
            }
            names += n.substringBefore('$')
        }
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitField(a: Int, name: String, d: String?, s: String?, v: Any?): FieldVisitor? {
                add(name); return null
            }

            override fun visitMethod(a: Int, name: String, d: String?, s: String?, e: Array<out String>?): MethodVisitor? {
                add(name); return null
            }
        }, ClassReader.SKIP_CODE)
        return names
    }
}
