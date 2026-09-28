@file:afield.CompletionCategory("Synthesis")
package afield.pd

import java.nio.file.Files
import java.nio.file.Path

/**
 * The objects this Pd can create, and the nearest of them to one it could not.
 *
 * The list is `pd-objects.txt`, which `tools/build_libpd_native.py` writes beside the library from the
 * same pinned sources it compiled: every `class_new` and `class_addcreator` name in Pd's `src` and
 * `extra`. So it names exactly what [PdLibrary.resolvedPath] can make, vanilla objects and their
 * aliases (`r`, `t`, `hsl`), and nothing a Pd distribution adds on top. Without a built libpd it is
 * empty and [suggest] says nothing.
 */
object PdObjects {
    /** The file [catalog] reads, beside the library. */
    const val FILE_NAME = "pd-objects.txt"

    private var cachedFrom: Pair<Path, Long>? = null
    private var cached: Set<String> = emptySet()

    /** The names in the catalog beside the resolved libpd; empty when there is none. */
    @Synchronized
    fun catalog(): Set<String> {
        val file = PdLibrary.resolvedPath?.parent?.resolve(FILE_NAME)?.takeIf { Files.isRegularFile(it) } ?: return emptySet()
        val stamp = file to Files.getLastModifiedTime(file).toMillis()
        if (stamp != cachedFrom) {
            cached = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            cachedFrom = stamp
        }
        return cached
    }

    /**
     * What [text] — an object box's contents, `osc 440` — probably meant, as whole corrected texts
     * (`osc~ 440`); empty when nothing in [known] is close.
     *
     * In order: the signal or control twin (`osc` → `osc~`, `metro~` → `metro`), a difference of case
     * only, then the names within two edits (one for names of three letters or fewer), the closest
     * only; up to three are offered when they are equally close. A name Pd *does* know gives nothing —
     * that object failed for another reason, usually its arguments.
     */
    fun suggest(text: String, known: Set<String> = catalog()): List<String> {
        val words = text.trim().split(Regex("\\s+"))
        val name = words.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (known.isEmpty() || name in known) return emptyList()
        val args = words.drop(1).joinToString(" ")
        fun with(n: String) = if (args.isEmpty()) n else "$n $args"

        val twin = if (name.endsWith("~")) name.dropLast(1) else "$name~"
        if (twin in known) return listOf(with(twin))
        known.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return listOf(with(it)) }

        // Compare against single names only: "list append" is the object "list" with an argument.
        val candidates = known.filter { ' ' !in it }
        val limit = if (name.length <= 3) 1 else 2
        val scored = candidates.map { it to editDistance(name, it) }.filter { it.second <= limit }
        val best = scored.minOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }.sorted().take(3).map(::with)
    }

    /** "did you mean [osc~ 440]?" for [text], or "" when [suggest] has nothing. */
    fun hint(text: String, known: Set<String> = catalog()): String {
        val offers = suggest(text, known).map { "[$it]" }
        return when (offers.size) {
            0 -> ""
            1 -> "did you mean ${offers[0]}?"
            else -> "did you mean " + offers.dropLast(1).joinToString(", ") + " or " + offers.last() + "?"
        }
    }

    /** Optimal-string-alignment distance: insertions, deletions, substitutions and adjacent swaps. */
    internal fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
