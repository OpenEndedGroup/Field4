@file:afield.CompletionCategory("Graphics")

package afield.four

import java.io.File

/**
 * What a shader failure on macOS means, when the cause is Metal rather than your GLSL.
 *
 * On macOS Field's Vulkan runs through MoltenVK, which translates each shader to the Metal Shading
 * Language and compiles it with `using namespace metal;` in scope. A function of yours named like a type in
 * Metal's standard library — `decay` and `enable_if` from its type traits, `array` from its
 * containers — then collides with it, and the compile fails with Metal's report ("reference to 'decay' is
 * ambiguous … metal::decay"), in the translated source's line numbers, which Vulkan passes on
 * only as "Error creating graphics pipeline: -3". [explain] turns that report into what happened and what
 * to do; [reservedNames] is the list, read from the Metal headers on this machine, so a name can be refused
 * before anything is compiled.
 */
object MetalShaderErrors {
    private val ambiguous = Regex("""reference to '([A-Za-z_][A-Za-z0-9_]*)' is ambiguous""")
    private val metalCandidate = Regex("""'metal::([A-Za-z_][A-Za-z0-9_]*)'""")

    /** What [message] — Vulkan's report of a Metal compile failure — means, in plain words; null when it is not a name collision. */
    fun explain(message: String): String? {
        val name = ambiguous.find(message)?.groupValues?.get(1)
            ?: metalCandidate.find(message)?.groupValues?.get(1)?.takeIf { "is ambiguous" in message || "redefinition" in message }
            ?: return null
        if (metalCandidate.findAll(message).none { it.groupValues[1] == name }) return null
        return "'$name' in your shader has the same name as metal::$name in Metal's standard library. On macOS the shader runs " +
            "through MoltenVK, which translates it to Metal with `using namespace metal;`, so the two collide. Rename '$name' — " +
            "a function of yours, or a stack.auto slot, whose name is its function's."
    }

    @Volatile private var last: Pair<Long, String>? = null

    /** Record [message] if it explains a Metal failure, and return the explanation. */
    fun note(message: String): String? = explain(message)?.also { last = System.currentTimeMillis() to it }

    /** The explanation of a Metal failure noted in the last few seconds, for the pipeline error that follows it; null otherwise. */
    fun recent(withinMs: Long = 10_000): String? = last?.takeIf { System.currentTimeMillis() - it.first < withinMs }?.second

    /**
     * The names of the types Metal's standard library declares, read from the newest set of Metal headers on
     * this machine; empty where there are none (not macOS). A function in a shader named like one of these
     * does not build on macOS.
     *
     * Only types: a function of yours that shares a name with one of Metal's functions (`select`, `powr`) is
     * one more overload, and those Metal would confuse with GLSL's own (`saturate`, `mix`) SPIRV-Cross
     * renames on the way. Both were tried on 2026-09-28 and built; `decay`, a type, did not.
     */
    val reservedNames: Set<String> by lazy {
        runCatching {
            val versions = File("/System/Library/PrivateFrameworks/GPUCompiler.framework/Versions").listFiles().orEmpty()
            val headers = versions.flatMap { v -> File(v, "Libraries/lib/clang").listFiles().orEmpty().map { File(it, "include/metal") } }
                // Every version's directory carries the same timestamp, and older ones can be empty: take the fullest.
                .filter { it.isDirectory }.maxByOrNull { d -> d.walkTopDown().count { it.isFile } }?.takeIf { d -> d.walkTopDown().any { it.isFile } }
                ?: return@runCatching emptySet<String>()
            val type = Regex("""^\s*(?:template\s*<[^>]*>\s*)?(?:struct|class)\s+([a-z_][a-z0-9_]*)""", RegexOption.MULTILINE)
            headers.walkTopDown().filter { it.isFile }.flatMap { f -> type.findAll(runCatching { f.readText() }.getOrDefault("")).map { it.groupValues[1] } }.toSet()
        }.getOrDefault(emptySet())
    }
}
