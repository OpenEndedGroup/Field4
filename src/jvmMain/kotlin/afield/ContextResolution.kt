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
 * arbitrary.
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
            Search(Resolution(mechanism, query.description, box, first, predecessor, emptyList()), examined)

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
            for (ancestor in next) {
                examined++
                collect(ancestor, depth, query, policy, isOwn = false, into = found) { return finish(it, examined) }
            }
            fringe = next
        }

        if (found.isNullOrEmpty()) return Search(null, examined)
        val first = found.first()
        val shadowed = found.drop(1).map { it.toCandidate(box, predecessor) }
        return Search(Resolution(mechanism, query.description, box, first, predecessor, shadowed), examined)
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
                val vars = b.liveKi?.context?.vars ?: continue
                val names = query.names
                if (names == null) {
                    for ((key, info) in vars.entries) {
                        if (query.acceptVariable(info)) {
                            val hit = Found(b, info.value, MatchKind.VARIABLE, key, depth)
                            if (into == null) onFirst(hit) else into += hit
                        }
                    }
                } else {
                    for (name in names) {
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
            val INHERITANCE_FROM_CONTEXT = Policy(false, OwnVariables.ALL, SearchOrder.VARIABLES_THEN_PROPERTIES)
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
    ) {
        val value: Any? = winner.value
        val supplier: Box = winner.supplier
        val matchKind: MatchKind = winner.kind
        val matchedName: String? = winner.name
        val depth: Int = winner.depth

        /** Asking box first, supplier last; computed on first read. */
        val path: List<Box> by lazy { pathTo(consumer, supplier, predecessor) }
    }
}
