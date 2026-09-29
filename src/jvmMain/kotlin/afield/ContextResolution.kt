package afield

/**
 * The one walk behind every "where does this value come from?" question a box can ask of the graph.
 *
 * Field had four walkers that differed in order and in whether the asking box's own variables
 * counted — `fromContext` / `fromContextWithNameOrNull` (`afield.four.modules`), `Inheritance`
 * (`prop` / `rootProp`), `InheritanceFromContext` (what `@exported` reads through) and the Monaco
 * hover's "inherited from" — plus a fifth, the hover, that could name a different supplier than
 * the delegate it was describing. They now all call [walk], and differ only in their
 * [Query] (what counts as a match) and [Policy] (which parts of each box are looked
 * at, in which order). See `dev_docs/ARCHITECTURE_REVIEW_2026-09-28.md`, section 1.
 *
 * ## The walk
 *
 * The asking box first (depth 0), then its ancestors breadth-first, one generation at a time. The
 * box itself can be the answer ([Policy.selfCandidate]); its own properties are always
 * searched; its own variables per [Policy.ownVariables]. Every ancestor has its properties
 * and variables searched in [Policy.order]. The first match wins, so nearer beats farther.
 *
 * Within one generation, ancestors are visited in the order of each box's `parents` set, which is
 * edge insertion order (a `CopyOnWriteArraySet`, restored in saved order on load) — stable, not
 * arbitrary. Within one box, a by-type query meets variables in declaration order
 * (`KotlinContext.vars` is a `LinkedHashMap` since 2026-09-29; it was a `HashMap`, so a tie between
 * two same-typed variables used to resolve in hash order).
 *
 * When the winner is an ancestor and another ancestor of the *same* generation also matches with a
 * different value, the pick is still the first in `parents` order, but the [Resolution] records the
 * others as [Resolution.competing] and the walk says so once on the `warning,context` log channel.
 * An identical value (the same `Renderer` reached two ways) is not an ambiguity.
 *
 * Variables are read from an engine only if the box already has one ([Box.liveKi]): an ancestor
 * that never ran has no variables, and building it an engine just to find that out was a side
 * effect of the old walkers, not a feature.
 *
 * ## Cost
 *
 * The ordinary call returns at the first match and allocates one [Resolution]; the path is not
 * built unless read. [walk] with `explain = true` keeps going through the whole graph and records
 * every other match as [Resolution.shadowed] — that is for tools and hovers, not hot paths.
 */
object ContextResolution {

    /**
     * Resolve [query] from [box] under [policy], or null if nothing up the graph matches.
     *
     * With [explain] the walk visits every reachable ancestor and the result carries every losing
     * match in [Resolution.shadowed].
     */
    fun walk(
        box: Box,
        query: Query,
        policy: Policy,
        mechanism: Mechanism,
        explain: Boolean = false,
    ): Resolution? = search(box, query, policy, mechanism, explain).resolution

    /**
     * "Why does [box] resolve [name] (or a value of [type]) where it does?" as JSON: the mechanism, the
     * supplier, the path of boxes, the match kind, the shadowed and competing candidates, and which
     * sourced preamble line — if any — declared the name. The entry point behind the backdoor's `why`,
     * the MCP tool `field_why` and the AI tool `WhyDoesThisResolve`; see [ContextWhy].
     */
    fun explain(box: Box, name: String? = null, type: String? = null): kotlinx.serialization.json.JsonObject =
        ContextWhy.explain(box, name, type)

    /** A walk's answer plus how many ancestors it examined — the count `Inheritance` reports. */
    class Search(val resolution: Resolution?, val ancestorsExamined: Int)

    /** [walk], also reporting how many ancestors were examined before it stopped. */
    fun search(
        box: Box,
        query: Query,
        policy: Policy,
        mechanism: Mechanism,
        explain: Boolean = false,
    ): Search {
        val predecessor = HashMap<Box, Box?>()
        predecessor[box] = null
        val found = if (explain) mutableListOf<Found>() else null

        fun finish(first: Found, examined: Int): Search =
            Search(Resolution(mechanism, query.description, box, first, predecessor, emptyList(), emptyList()), examined)

        // depth 0: the asking box
        if (policy.selfCandidate && query.acceptSelf?.invoke(box) == true) {
            val hit = Found(box, box, MatchKind.SELF, null, 0)
            if (found == null) return finish(hit, 0)
            found += hit
        }
        collect(box, 0, query, policy, isOwn = true, into = found) { return finish(it, 0) }

        var fringe: Collection<Box> = listOf(box)
        var depth = 0
        var examined = 0
        while (fringe.isNotEmpty()) {
            depth++
            val next = LinkedHashSet<Box>()
            for (b in fringe) {
                for (parent in b.parents) {
                    if (parent !in predecessor) {
                        predecessor[parent] = b
                        next += parent
                    }
                }
            }
            if (found != null) {
                for (ancestor in next) {
                    examined++
                    collect(ancestor, depth, query, policy, isOwn = false, into = found) { error("unreachable") }
                }
            } else {
                val generation = next.toList()
                for ((i, ancestor) in generation.withIndex()) {
                    examined++
                    val hit = firstIn(ancestor, depth, query, policy) ?: continue
                    // Finish this generation: an equidistant supplier holding a different value is
                    // an ambiguity. The pick stays deterministic (parents order); it is reported.
                    val competing = ArrayList<Candidate>(0)
                    for (j in i + 1 until generation.size) {
                        val other = firstIn(generation[j], depth, query, policy) ?: continue
                        if (other.value !== hit.value && other.value != hit.value) {
                            competing += other.toCandidate(box, predecessor)
                        }
                    }
                    val resolution = Resolution(mechanism, query.description, box, hit, predecessor, emptyList(), competing)
                    if (competing.isNotEmpty()) warnAmbiguity(resolution)
                    return Search(resolution, examined)
                }
            }
            fringe = next
        }

        if (found.isNullOrEmpty()) return Search(null, examined)
        val first = found.first()
        val rest = found.drop(1)
        val shadowed = rest.map { it.toCandidate(box, predecessor) }
        val competing = rest
            .filter { it.depth == first.depth && it.depth > 0 && it.supplier !== first.supplier }
            .filter { it.value !== first.value && it.value != first.value }
            .distinctBy { it.supplier }
            .map { it.toCandidate(box, predecessor) }
        return Search(Resolution(mechanism, query.description, box, first, predecessor, shadowed, competing), examined)
    }

    private fun firstIn(b: Box, depth: Int, query: Query, policy: Policy): Found? {
        collect(b, depth, query, policy, isOwn = false, into = null) { return it }
        return null
    }

    private val warnedAmbiguities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Say once — per asking box, mechanism, query and set of suppliers — that two equidistant
     * ancestors both answered with different values, and which one was picked.
     */
    private fun warnAmbiguity(r: Resolution) {
        val suppliers = listOf(r.supplier) + r.competing.map { it.supplier }
        val key = "${r.consumer.id}|${r.mechanism}|${r.query}|" + suppliers.joinToString(",") { it.id }
        if (!warnedAmbiguities.add(key)) return
        val names = suppliers.joinToString(", ") { "'${it.name.ifBlank { it.id }}'" }
        val consumer = r.consumer.name.ifBlank { r.consumer.id }
        val picked = r.supplier.name.ifBlank { r.supplier.id }
        val mechanism = r.mechanism.name.lowercase()
        afield.logger.lprintln("warning,context", "ambiguous $mechanism lookup of '${r.query}' from '$consumer': equidistant ancestors $names hold different values; using '$picked' (first in parents order)")
    }

    /**
     * Search one box. Without [into], stops at the first match by calling [onFirst] (which does
     * not return); with it, appends every match in search order.
     */
    private inline fun collect(
        b: Box,
        depth: Int,
        query: Query,
        policy: Policy,
        isOwn: Boolean,
        into: MutableList<Found>?,
        onFirst: (Found) -> Nothing,
    ): Boolean {
        val readVariables = if (isOwn) policy.ownVariables != OwnVariables.NONE else true
        val onlyExported = isOwn && policy.ownVariables == OwnVariables.EXPORTED
        val sources = when (policy.order) {
            SearchOrder.PROPERTIES_ONLY -> PROPS
            SearchOrder.PROPERTIES_THEN_VARIABLES -> if (readVariables) PROPS_VARS else PROPS
            SearchOrder.VARIABLES_THEN_PROPERTIES -> if (readVariables) VARS_PROPS else PROPS
        }
        for (source in sources) {
            if (source == MatchKind.PROPERTY) {
                val names = query.names
                if (names == null) {
                    for ((key, value) in b.properties.entries) {
                        if (query.acceptProperty(value)) {
                            val hit = Found(b, value, MatchKind.PROPERTY, key, depth)
                            if (into == null) onFirst(hit) else into += hit
                        }
                    }
                } else {
                    for (name in names) {
                        val value = b.getPropertyRaw(name)
                        if (query.acceptProperty(value)) {
                            val hit = Found(b, value, MatchKind.PROPERTY, name, depth)
                            if (into == null) onFirst(hit) else into += hit
                        }
                    }
                }
            } else {
                val context = b.liveKi?.context ?: continue
                val vars = context.vars
                val names = query.names
                if (names == null) {
                    for ((key, info) in vars.entries) {
                        if (onlyExported && key !in context.exportedVariables) continue
                        if (query.acceptVariable(info)) {
                            val hit = Found(b, info.value, MatchKind.VARIABLE, key, depth)
                            if (into == null) onFirst(hit) else into += hit
                        }
                    }
                } else {
                    for (name in names) {
                        if (onlyExported && name !in context.exportedVariables) continue
                        val info = vars[name] ?: continue
                        if (query.acceptVariable(info)) {
                            val hit = Found(b, info.value, MatchKind.VARIABLE, name, depth)
                            if (into == null) onFirst(hit) else into += hit
                        }
                    }
                }
            }
        }
        return false
    }

    private val PROPS = listOf(MatchKind.PROPERTY)
    private val PROPS_VARS = listOf(MatchKind.PROPERTY, MatchKind.VARIABLE)
    private val VARS_PROPS = listOf(MatchKind.VARIABLE, MatchKind.PROPERTY)

    /** One match, before it has been turned into a public [Candidate]. */
    internal class Found(val supplier: Box, val value: Any?, val kind: MatchKind, val name: String?, val depth: Int) {
        fun toCandidate(consumer: Box, predecessor: Map<Box, Box?>) =
            Candidate(value, supplier, kind, name, depth, pathTo(consumer, supplier, predecessor))
    }

    internal fun pathTo(consumer: Box, supplier: Box, predecessor: Map<Box, Box?>): List<Box> {
        val reversed = ArrayList<Box>()
        var at: Box? = supplier
        while (at != null) {
            reversed += at
            if (at === consumer) break
            at = predecessor[at]
        }
        reversed.reverse()
        return reversed
    }

    /** Which user-facing mechanism asked — recorded on the [Resolution] so an explanation can name it. */
    enum class Mechanism {
        /** `fromContext<T>()`: by type. */
        FROM_CONTEXT,

        /** `fromContextWithNameOrNull<T>(name)`: by name, then assignability. */
        FROM_CONTEXT_NAMED,

        /** `Inheritance<T>` — what `prop` / `rootProp` generate: raw properties only. */
        INHERITANCE,

        /** `InheritanceFromContext<T>` — what `@exported` generates: live variables, then properties. */
        INHERITANCE_FROM_CONTEXT,
    }

    /** What kind of slot supplied the value. */
    enum class MatchKind {
        /** The asking box is itself the value (`fromContext<Box>()`). */
        SELF,

        /** An entry in a box's property bag. */
        PROPERTY,

        /** A top-level variable in a box's Kotlin engine (`ki.context.vars`). */
        VARIABLE,
    }

    /** How much of the asking box's own variable table a walk may use. */
    enum class OwnVariables {
        /** None: the self-pinning rule. A value this box computed on its last run cannot answer. */
        NONE,

        /** All of them. */
        ALL,

        /**
         * Only the ones this box itself `@exported` (or had exported into it, for `@rootExported` /
         * `@metarootExported`): [KotlinContext.exportedVariables]. The exporter can read its own
         * export; an ordinary leftover `val x = _x` cannot answer for `_x`.
         */
        EXPORTED,
    }

    /** The order the two slot kinds are searched in, within every box on the walk. */
    enum class SearchOrder { PROPERTIES_THEN_VARIABLES, VARIABLES_THEN_PROPERTIES, PROPERTIES_ONLY }

    /**
     * Which parts of each box a walk looks at.
     *
     * @property selfCandidate whether the asking box can itself be the answer (a by-type query for a
     * type the box is)
     * @property ownVariables how much of the asking box's own variable table is searched
     * @property order the slot order used in every box, the asking box included
     */
    data class Policy(
        val selfCandidate: Boolean,
        val ownVariables: OwnVariables,
        val order: SearchOrder,
    ) {
        companion object {
            /** `fromContext` / `fromContextWithNameOrNull`: properties, then variables; own variables on request. */
            fun fromContext(includeLocalVariables: Boolean, selfCandidate: Boolean = true) = Policy(
                selfCandidate = selfCandidate,
                ownVariables = if (includeLocalVariables) OwnVariables.ALL else OwnVariables.NONE,
                order = SearchOrder.PROPERTIES_THEN_VARIABLES,
            )

            /** `Inheritance` (`prop`, `rootProp`) and anything else that reads raw properties only. */
            val INHERITANCE = Policy(false, OwnVariables.NONE, SearchOrder.PROPERTIES_ONLY)

            /** `InheritanceFromContext` (`@exported`): variables, then properties, in every box. */
            val INHERITANCE_FROM_CONTEXT = Policy(false, OwnVariables.EXPORTED, SearchOrder.VARIABLES_THEN_PROPERTIES)
        }
    }

    /**
     * What counts as a match.
     *
     * A by-type query ([names] null) tests every property value and every variable; a by-name query
     * tests only the slots called one of [names], in that order, within each slot kind.
     */
    class Query(
        val description: String,
        val names: List<String>?,
        val acceptProperty: (Any?) -> Boolean,
        val acceptVariable: (KotlinVariableInfo) -> Boolean,
        val acceptSelf: ((Box) -> Boolean)? = null,
    ) {
        companion object {
            /** Anything [accept] says is a `T` — the `fromContext<T>()` query. */
            fun byType(type: Class<*>, accept: (Any?) -> Boolean) = Query(
                description = type.name,
                names = null,
                acceptProperty = accept,
                acceptVariable = { accept(it.value) },
                acceptSelf = { accept(it) },
            )

            /** A non-null property called one of [names]; variables never match. The `Inheritance` query. */
            fun propertyNamed(vararg names: String) = Query(
                description = names.first(),
                names = names.toList(),
                acceptProperty = { it != null },
                acceptVariable = { false },
            )

            /**
             * The `InheritanceFromContext` query for the property called [propertyName]: a leading
             * underscore is optional, so `_speed` also matches a slot called `speed`; a variable
             * matches even when it holds null, a property only when it is non-null.
             */
            fun inheritedFromContext(propertyName: String): Query {
                val names = linkedSetOf(propertyName)
                if (propertyName.startsWith("_")) names.add(propertyName.substring(1))
                return Query(propertyName, names.toList(), { it != null }, { true })
            }
        }
    }

    /** One slot that matched a query, and how the walk got there. */
    class Candidate(
        val value: Any?,
        val supplier: Box,
        val matchKind: MatchKind,
        /** The property key or variable name that matched; null for [MatchKind.SELF]. */
        val matchedName: String?,
        /** Generations from the asking box: 0 is the box itself. */
        val depth: Int,
        /** Asking box first, supplier last. */
        val path: List<Box>,
    )

    /**
     * The answer to one walk: the winning slot, the route to it, and — when asked for — what lost.
     */
    class Resolution internal constructor(
        val mechanism: Mechanism,
        /** The type name or slot name that was asked for. */
        val query: String,
        /** The box that asked. */
        val consumer: Box,
        winner: Found,
        private val predecessor: Map<Box, Box?>,
        /** Every other match, in the order the walk met them. Filled only by an `explain` walk. */
        val shadowed: List<Candidate>,
        /**
         * Equidistant ancestors other than [supplier] that also matched, with a different value — an
         * ambiguity. The winner is still the first in `parents` order; a non-explain walk also
         * reports this once on the `warning,context` log channel. Empty when there is no tie.
         */
        val competing: List<Candidate>,
    ) {
        /** True when an equidistant, different supplier also answered with a different value. */
        val ambiguous: Boolean get() = competing.isNotEmpty()

        val value: Any? = winner.value
        val supplier: Box = winner.supplier
        val matchKind: MatchKind = winner.kind
        val matchedName: String? = winner.name
        val depth: Int = winner.depth

        /** Asking box first, supplier last; computed on first read. */
        val path: List<Box> by lazy { pathTo(consumer, supplier, predecessor) }
    }
}
