package afield

import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a receiver type's [CommonMembers] declaration into the ranking prior the popup consumes
 * (`Metadata.commonMemberRank`, scored by `COMMON_MEMBER_BASE` in `fieldSuggest.js`).
 *
 * ### Why this exists
 *
 * At an empty prefix — a bare `f.` — there is nothing to fuzzy-match, so `fieldSuggest.js` ranks on
 * category weight (0–28) plus the server's own order, which contributes about 3 points spread across the
 * whole list (`originalIndex * 0.012`). The term that *should* decide it is `recency`, worth up to ~62 —
 * but recency is `localStorage`, so it is empty for a new user, empty in a fresh profile, and empty in
 * every test. The popup therefore had no way to know that `moveTo` matters more than `breakNext`, and no
 * way for a test to assert that it should. This supplies one from the source.
 *
 * Authority is deliberately ordered `recency` > this prior > category > server order: what a person
 * actually does beats what the author guessed, which beats a bucket, which beats alphabetical.
 *
 * ### Resolution
 *
 * By qualified receiver name (`AaCompletionClient` forwards it as `receiverQualifiedName`), via
 * `Class.forName` and then up the superclass chain, nearest class winning — a subclass may re-order its
 * parent's list, and a receiver typed as a subclass still gets the parent's prior. Interfaces are walked
 * too, since a receiver is often typed as one.
 *
 * Both hits and misses are cached: this is on the completion path, which runs per keystroke, and the
 * overwhelmingly common answer is "this type has no annotation".
 */
object CommonMemberPrior {

    /** Score for the first-listed member. Below `recency`'s ~62 ceiling, above `CATEGORY_WEIGHT`'s 28. */
    const val BASE = 40

    /** Points shed per position, so a declared order survives into the popup without swamping it. */
    const val STEP = 2

    private val byReceiver = ConcurrentHashMap<String, Map<String, Int>>()
    private val NONE: Map<String, Int> = emptyMap()

    /**
     * 1-based position of [memberName] in [receiverQualifiedName]'s list, or 0 if either is absent.
     *
     * [memberName] is matched on the bare name, so `moveTo(x: Number, y: Number)` and `moveTo(v: Vec3)`
     * both resolve — the caller may pass a display string.
     */
    fun rankOf(receiverQualifiedName: String?, memberName: String?): Int {
        if (receiverQualifiedName.isNullOrBlank() || memberName.isNullOrBlank()) return 0
        val ranks = ranksFor(receiverQualifiedName)
        if (ranks.isEmpty()) return 0
        return ranks[bareName(memberName)] ?: 0
    }

    /** Turns a rank into the client-side ranking term. Rank 0 scores nothing; the list decays by [STEP]. */
    fun scoreOf(rank: Int): Int = if (rank <= 0) 0 else (BASE - (rank - 1) * STEP).coerceAtLeast(1)

    /** Declared common members of [receiverQualifiedName], name to 1-based position. Empty if none. */
    fun ranksFor(receiverQualifiedName: String?): Map<String, Int> {
        val key = receiverQualifiedName?.trim()?.takeIf { it.isNotBlank() } ?: return NONE
        return byReceiver.getOrPut(key) { computeRanks(key) }
    }

    /**
     * Names declared directly on [type]'s own annotation, in declared order — what the guard test checks
     * against the class's actual members. Deliberately *not* inherited: a class is only answerable for the
     * list it writes.
     */
    fun declaredNames(type: Class<*>): List<String> =
        type.getAnnotation(CommonMembers::class.java)?.value?.toList().orEmpty()

    private fun computeRanks(qualifiedName: String): Map<String, Int> {
        val type = loadClass(qualifiedName) ?: return NONE
        // Nearest declaration wins, so visit the class before anything it inherits from and let the first
        // writer of a name keep its position.
        val ordered = LinkedHashMap<String, Int>()
        for (candidate in selfThenSupertypes(type)) {
            val names = declaredNames(candidate)
            if (names.isEmpty()) continue
            for (name in names) {
                val bare = bareName(name)
                if (bare.isNotBlank() && bare !in ordered) ordered[bare] = ordered.size + 1
            }
        }
        return if (ordered.isEmpty()) NONE else ordered
    }

    /** Breadth-first over superclasses and interfaces, self first, each class visited once. */
    private fun selfThenSupertypes(type: Class<*>): List<Class<*>> {
        val seen = LinkedHashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue += type
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == Any::class.java || !seen.add(next)) continue
            next.superclass?.let { queue += it }
            queue += next.interfaces
        }
        return seen.toList()
    }

    private fun loadClass(qualifiedName: String): Class<*>? = runCatching {
        Class.forName(qualifiedName, false, CommonMemberPrior::class.java.classLoader)
    }.getOrNull()

    /** `moveTo(v: Vec3)` / `moveTo<T>` / `FLine.moveTo` all name the member `moveTo`. */
    private fun bareName(raw: String): String =
        raw.substringBefore('<').substringBefore('(').substringAfterLast('.').trim()
}
