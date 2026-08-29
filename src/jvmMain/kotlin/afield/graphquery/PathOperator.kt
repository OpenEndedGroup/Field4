package afield.graphquery

/**
 * The `/` operator: `rig / "**\/freq"` is [graphPaths] with the ceremony removed.
 *
 * ```kotlin
 * (rig / "**\/freq").whereValueIs<Float> { it > 300f }
 * ```
 *
 * Path syntax already uses `/` as its separator, so the operator and the string it takes are
 * spelled the same way — `rig / "carrier" / "freq"` and `rig / "carrier/freq"` are the same
 * query, and both report `carrier/freq`.
 *
 * ### What defining this on `Any?` costs
 *
 * It is a deliberately wide extension, in the same spirit as the existing
 * `patch matches "**\/cutoff"`, and it is auto-imported into every box. Three consequences
 * worth knowing:
 *
 * - **Nothing that used to compile changes meaning.** A member operator always beats an
 *   extension, so every `Float / Float`, `Vec3 / Float`, `Signal / Signal` keeps its own
 *   `div`. Between two extensions the more specific receiver wins, so
 *   `kotlin.io.path`'s `Path / "sub"` still builds a `Path`. What this adds is only the case
 *   that was previously a compile error: a *String* on the right of `/`.
 * - **That case is no longer a compile error.** `"a" / "b"` now type-checks and returns an
 *   empty [PathSet] instead of being rejected, and a genuine typo — dividing by a string you
 *   meant to parse — surfaces later as a `PathSet` type mismatch rather than at the `/`.
 *   That is the whole of the risk, and it is the price of the notation.
 * - **`div` appears on every receiver in completion.** One extra entry, everywhere.
 *
 * When you need [GraphWalkOptions] — a depth cap, private properties, a custom `descendInto`
 * — call [graphPaths] directly; the operator has nowhere to put them and does not try.
 */
operator fun Any?.div(pattern: String): PathSet = graphPaths(pattern)

/**
 * Continue a query from a set: match [pattern] against the graph below *each* ref, with the
 * results re-addressed under the ref they came from.
 *
 * This exists so a chain keeps meaning what it reads like. Without it `(rig / "carrier") /
 * "freq"` would fall through to the [Any?][div] overload and reflect into the [PathSet]
 * object itself, reporting the query's own fields.
 */
operator fun PathSet.div(pattern: String): PathSet = descend(root, refs, pattern)

/** Continue a query from one slot — `(rig / "carrier").first() / "freq"`. */
operator fun GraphPathRef.div(pattern: String): PathSet {
    // Read the live slot once: a getter is allowed to have side effects, and the docs page
    // measures edge reads.
    val value = get() ?: return PathSet(null, emptyList())
    return PathSet(value, value.graphPaths(pattern).refs.map { it.rebased(pathSegments) })
}

/**
 * Continue a query from a typed set. Typed leaves are usually the end of the road, so this
 * normally returns nothing; it is here because falling through to the [Any?][div] overload
 * would instead report the [LensSet]'s own internals.
 */
operator fun <T : Any> LensSet<T>.div(pattern: String): PathSet =
    descend(null, lenses.map { it.ref }, pattern)

/** Continue a query from one typed slot. See [LensSet.div]. */
operator fun <T : Any> Lens<T>.div(pattern: String): PathSet = ref / pattern

/**
 * Shared body of the continuing overloads: walk each ref's current value and rebase what
 * comes back onto that ref's address. A null slot contributes nothing.
 */
private fun descend(root: Any?, refs: List<GraphPathRef>, pattern: String): PathSet {
    val compiled = GraphQuery.compile(pattern)
    val out = mutableListOf<GraphPathRef>()
    refs.forEach { ref ->
        val value = ref.get() ?: return@forEach
        compiled.resolve(value).refs.forEach { out += it.rebased(ref.pathSegments) }
    }
    return PathSet(root, out)
}
