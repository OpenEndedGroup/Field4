package afield

import afield.macros.gatherPreambleUpwards

/**
 * Documentation for the shader bindings a box's preamble declares, read back out of the preamble itself.
 *
 * The last leg of a comment's journey out of a shader. A note written at the end of a push-constant
 * member's declaration —
 *
 * ```glsl
 * layout(push_constant) uniform Push {
 *     float time;   // seconds since the box started
 * } pc;
 * ```
 *
 * is scraped from the GLSL source by `afield.four.modules.parseGlslMemberComments`, appended by
 * `afield.stdlib.insertForShader` (and its compute / mesh / ray-tracing siblings) to every generated
 * accessor for that member, and lands in the preamble looking like this:
 *
 * ```kotlin
 * var Mesh.time by Mesh.BoundFromStructure<Float>() // seconds since the box started
 * ```
 *
 * This object turns that back into completion documentation, so `m.tim⇥` explains itself. Reading the
 * preamble — rather than holding the live `Introspection` in a registry — is deliberate: the preamble is
 * saved with the document, so the note survives a restart and is there for the first completion of a
 * session, before any shader has been reloaded. It also means a hand-written binding in a preamble gets
 * the same treatment, which is the behaviour a person would predict from looking at the text.
 *
 * Neither completion backend can supply this: the AA sidecar ships no KDoc at all, and
 * `KotlinSourceIndex` indexes the repository's own sources, not a box's generated preamble. So
 * [indexFor] is consulted directly by `KotlinExecution`'s candidate loop.
 */
object ShaderBindingDocs {

    /**
     * A preamble's binding comments, resolved once and then asked about each candidate row.
     *
     * Keyed by `Receiver.member` for the accessors that name a receiver (`var Mesh.time by …`) or that
     * sit in a generated structure class (`class Params { var kali0 by … }`), and by the bare member
     * name for the case where the completion backend names no receiver at all. [byName] holds only
     * *unambiguous* names — the same rule the GLSL-side scrape uses — so two bindings sharing a member
     * name with different comments cancel out rather than one being asserted about the other's rows.
     */
    class Lookup internal constructor(
        private val byReceiver: Map<String, String>,
        private val byName: Map<String, String>
    ) {
        val isEmpty: Boolean get() = byReceiver.isEmpty() && byName.isEmpty()

        /**
         * The comment a shader binding carries, or null.
         *
         * A named receiver decides ALONE — no falling back to the bare name when it misses. The row
         * would otherwise be somebody else's member that happens to share a push constant's name, and
         * `m.size` on a `List` explaining itself as "seconds since the box started" is worse than
         * saying nothing.
         *
         * @param member the candidate's simple name
         * @param receiverType the receiver the backend named, qualified or not; null when it named none
         */
        fun documentationFor(member: String, receiverType: String?): String? {
            if (member.isBlank() || isEmpty) return null
            val simple = receiverType?.substringAfterLast('.')?.substringBefore('<')?.substringBefore('?')
            if (!simple.isNullOrBlank()) return byReceiver["$simple.$member"]
            return byName[member]
        }
    }

    private val empty = Lookup(emptyMap(), emptyMap())

    /** One entry, so an unchanged preamble is parsed once rather than once per completion request. */
    private var cachedPreamble: String? = null
    private var cachedLookup: Lookup = empty

    /** `[/*marker*/] var Receiver.member by …` or, inside a generated structure class, `var member by …`. */
    private val declarationRegex = Regex(
        """\b(?:var|val)\s+(?:([A-Za-z_][A-Za-z0-9_.]*)\.)?`?([A-Za-z_][A-Za-z0-9_]*)`?\s*(?:\bby\b|:)"""
    )
    private val classRegex = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    /**
     * The bindings documented for a box, from its *inherited* preamble — so an accessor declared by a
     * parent box documents the child's rows too, exactly as completion resolves it.
     *
     * Resolve this ONCE per completion request and ask the result about each row: gathering and parsing
     * a preamble per candidate would put a box walk and a full-text compare on a path that runs over a
     * thousand times per keystroke.
     */
    fun indexFor(box: Box): Lookup {
        val preamble = runCatching { gatherPreambleUpwards(box) }.getOrNull() ?: return empty
        if (preamble == cachedPreamble) return cachedLookup
        val lookup = parse(preamble)
        cachedPreamble = preamble
        cachedLookup = lookup
        return lookup
    }

    /** Parse a preamble text directly — for tests, and for any caller that already holds the text. */
    fun forPreamble(preamble: String): Lookup = parse(preamble)

    private fun parse(preamble: String): Lookup {
        if ("//" !in preamble) return empty
        val byReceiver = LinkedHashMap<String, String>()
        val byName = LinkedHashMap<String, String>()
        val ambiguous = mutableSetOf<String>()
        // The generated structure classes declare their members with no receiver, inside a marked
        // block. Track the class so those members are keyed by it.
        var enclosingClass: String? = null

        preamble.lines().forEach { line ->
            classRegex.find(line)?.let { enclosingClass = it.groupValues[1] }
            if (line.contains("/*end*/")) enclosingClass = null

            val commentAt = line.indexOf("//")
            if (commentAt < 0) return@forEach
            val comment = line.substring(commentAt + 2).trim()
            if (comment.isEmpty()) return@forEach
            val match = declarationRegex.find(line.substring(0, commentAt)) ?: return@forEach
            val member = match.groupValues[2]
            val receiver = match.groupValues[1].takeIf { it.isNotBlank() }?.substringAfterLast('.')
                ?: enclosingClass
            if (receiver != null) byReceiver["$receiver.$member"] = comment
            val seen = byName[member]
            if (seen == null) byName[member] = comment else if (seen != comment) ambiguous += member
        }

        return Lookup(byReceiver, byName.filterKeys { it !in ambiguous })
    }
}
