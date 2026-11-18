package afield.runtime.bytecode

import java.io.File

/**
 * How a pass's tests rewrite a class the test compiler built and run it: read its bytes, rewrite
 * them with some passes, define the result in a child loader and link it, which runs the bytecode
 * verifier (so a pass that broke a stack-map frame fails here, not in a box).
 *
 * The test class stands in for a snippet. Give its origin the test file's own text
 * ([testSource]) and its bytecode lines are box lines, so [SnippetOrigin.boxLine] is the identity.
 */
object BoxBytecodeTestSupport {

    fun bytesOf(c: Class<*>): ByteArray =
        c.getResourceAsStream("/" + c.name.replace('.', '/') + ".class")!!.readBytes()

    /** Define [bytes] as class [name] in a child of [parent] and link it. */
    fun load(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
        val loader = object : ClassLoader(parent) {
            override fun loadClass(n: String, resolve: Boolean): Class<*> =
                if (n == name) synchronized(this) { findLoadedClass(n) ?: defineClass(n, bytes, 0, bytes.size) }
                else super.loadClass(n, resolve)
        }
        return Class.forName(name, true, loader)
    }

    /** [c] rewritten by [passes] for [origin], loaded and linked; fails the test if it would not rewrite. */
    fun rewriteAndLoad(
        c: Class<*>,
        passes: List<BoxBytecodePass>,
        origin: SnippetOrigin = SnippetOrigin.detached("'test' (${c.simpleName})", loader = c.classLoader),
    ): Class<*> {
        val out = BoxBytecode.rewriteClass(bytesOf(c), origin, passes) ?: error("${c.name} did not rewrite with ${passes.map { it.id }}")
        return load(c.name, out, c.classLoader)
    }

    /** The text of a jvmTest source file, by path under `src/jvmTest/kotlin` (tests run from the project root). */
    fun testSource(path: String): String? =
        File("src/jvmTest/kotlin", path).takeIf { it.isFile }?.readText()

    /** An origin whose source is the jvmTest file at [path], so bytecode lines are its lines. */
    fun originFor(c: Class<*>, path: String, label: String = "'test' (${c.simpleName})"): SnippetOrigin {
        val text = testSource(path) ?: error("no test source at src/jvmTest/kotlin/$path (cwd ${File(".").absolutePath})")
        return SnippetOrigin.detached(label, text, c.classLoader)
    }
}
