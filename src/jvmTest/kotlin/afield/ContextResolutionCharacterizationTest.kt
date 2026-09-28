package afield

import afield.four.modules.fromContext
import afield.four.modules.fromContextWithNameOrNull
import afield.macros.PREAMBLE_SELF_SUPPLIER
import afield.macros.dropStaleSuppliedLines
import afield.macros.gatherPreambleUpwards
import afield.macros.gatherPreambleUpwardsReport
import afield.macros.stripPreambleSupplierTag
import afield.macros.withPreambleSupplierTag
import afield.stdlib.Inheritance
import afield.stdlib.InheritanceFromContext
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization of the four context walkers, as they stood on 2026-09-28 — before
 * `ContextResolution` (dev_docs/ARCHITECTURE_REVIEW_2026-09-28.md, section 1).
 *
 * Every fence here is **dated**. A fence that is later flipped on purpose says so, with the date and
 * the ledger entry in `dev_docs/stdlib_behaviour_changes.md` that records why; a fence that is not
 * flipped is a behaviour the refactor promised to keep.
 *
 * No Kotlin engine is started: each box gets a proxy engine whose only live member is `context`, so
 * the variable tables are exactly what the test put there. The live-engine half — a variable a box
 * really left behind, a preamble line `checkPreambleAndExecute` really persisted — is
 * `examples.context.resolution-ledger` (`ContextResolutionLedgerSmokeTest`).
 */
class ContextResolutionCharacterizationTest {

    class Token(val label: String) {
        override fun toString() = "Token($label)"
    }

    private val boxes = mutableListOf<Box>()

    private fun session(label: String): SessionInformation {
        val root = Box("$label-root")
        val s = SessionInformation(filename = "<$label>", documentName = label, root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        thisSessionInfo.set(s)
        boxes += root
        return s
    }

    private fun boxIn(s: SessionInformation, id: String): Box =
        Box(id).also {
            it.inside = s
            s.boxes[id] = it
            boxes += it
            it.ki = fakeEngine(KotlinContext())
        }

    private fun fakeEngine(context: KotlinContext): KotlinScriptEngine =
        Proxy.newProxyInstance(
            KotlinScriptEngine::class.java.classLoader,
            arrayOf(KotlinScriptEngine::class.java)
        ) { proxy, method, _ ->
            when (method.name) {
                "getContext" -> context
                "toString" -> "FakeEngine@${System.identityHashCode(proxy)}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                "dispose" -> null
                else -> throw UnsupportedOperationException("FakeEngine.${method.name}")
            }
        } as KotlinScriptEngine

    private fun Box.variable(name: String, value: Any?) {
        ki.context.vars[name] = KotlinVariableInfo(value, ::fixtureDescriptor, this)
    }

    private fun <T> Box.reading(block: () -> T): T {
        val previous = Box.context.get()
        Box.context.set(this)
        try {
            return block()
        } finally {
            if (previous == null) Box.context.remove() else Box.context.set(previous)
        }
    }

    @AfterTest
    fun cleanUp() {
        val mine = boxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        boxes.clear()
        thisSessionInfo.remove()
        Box.context.remove()
    }

    // ---------------------------------------------------------------------------------------------
    // fromContext / fromContextWithNameOrNull
    // ---------------------------------------------------------------------------------------------

    /** 2026-09-28, kept: per ancestor, properties are searched before that ancestor's variables. */
    @Test
    fun fromContextPrefersAnAncestorsPropertyOverItsVariable() {
        val s = session("fc-props-vars")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.variable("v", Token("variable"))
        parent.setPropertyRaw("p", Token("property"))

        assertEquals("property", child.reading { fromContext<Token>() }.label)
    }

    /** 2026-09-28, kept: the self-pinning fix (bafa9ee5f) — own variables only on request. */
    @Test
    fun fromContextSkipsItsOwnVariablesUnlessAsked() {
        val s = session("fc-own-vars")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.setPropertyRaw("p", Token("parent"))
        child.variable("mine", Token("mine"))

        assertEquals("parent", child.reading { fromContext<Token>() }.label)
        assertEquals("mine", child.reading { fromContext<Token>(includeLocalVariables = true) }.label)
        assertEquals(
            "parent",
            child.reading { fromContextWithNameOrNull<Token>("p") }?.label,
        )
    }

    /** 2026-09-28, kept: nearer beats farther, whatever the kind of match. */
    @Test
    fun fromContextNearerAncestorWins() {
        val s = session("fc-nearer")
        val grand = boxIn(s, "grand")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        grand.connect(parent)
        parent.connect(child)
        grand.setPropertyRaw("p", Token("grand"))
        parent.variable("v", Token("parent-variable"))

        assertEquals("parent-variable", child.reading { fromContext<Token>() }.label)
    }

    /**
     * 2026-09-28, kept: equidistant ancestors resolve in the order of the consumer's `parents` set —
     * edge insertion order — not "arbitrarily" as `Inheritance`'s KDoc said. Reversing the connection
     * order reverses the answer.
     */
    @Test
    fun fromContextEquidistantAncestorsFollowParentsOrder() {
        val s = session("fc-equidistant")
        val a = boxIn(s, "a")
        val b = boxIn(s, "b")
        val child = boxIn(s, "child")
        a.setPropertyRaw("p", Token("a"))
        b.setPropertyRaw("p", Token("b"))
        a.connect(child)
        b.connect(child)
        assertEquals("a", child.reading { fromContext<Token>() }.label)

        a.disconnect(child)
        a.connect(child)
        assertEquals("b", child.reading { fromContext<Token>() }.label)
    }

    /**
     * 2026-09-28: two same-typed variables in one ancestor resolved in `HashMap` iteration order.
     * FLIPPED 2026-09-29 (stdlib_behaviour_changes.md, "one context walk"): `KotlinContext.vars` is a
     * `LinkedHashMap`, so the first-declared wins. `early` lands in a later hash bucket than `late`,
     * so the two orders disagree — asserted, so this fence cannot become a tautology.
     */
    @Test
    fun fromContextVariableTieWithinOneBoxFollowsDeclarationOrder() {
        val s = session("fc-var-tie")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.variable("early", Token("early"))
        parent.variable("late", Token("late"))

        val hashOrderFirst = HashMap(parent.ki.context.vars).values.first().value as Token
        assertNotEquals("early", hashOrderFirst.label, "fixture premise: hash order must differ from declaration order")
        assertEquals("early", child.reading { fromContext<Token>() }.label)
    }

    /** 2026-09-29: equidistant ancestors with different values are an ambiguity, recorded, not hidden. */
    @Test
    fun anEquidistantTieIsRecordedAsAmbiguity() {
        val s = session("fc-ambiguity")
        val a = boxIn(s, "a")
        val b = boxIn(s, "b")
        val child = boxIn(s, "child")
        a.setPropertyRaw("p", Token("a"))
        b.setPropertyRaw("p", Token("b"))
        a.connect(child)
        b.connect(child)
        val query = ContextResolution.Query.byType(Token::class.java) { it is Token }
        val found = ContextResolution.walk(
            child, query, ContextResolution.Policy.fromContext(false), ContextResolution.Mechanism.FROM_CONTEXT,
        )!!
        assertEquals(a, found.supplier)
        assertTrue(found.ambiguous)
        assertEquals(listOf(b), found.competing.map { it.supplier })

        val explained = ContextResolution.walk(
            child, query, ContextResolution.Policy.fromContext(false), ContextResolution.Mechanism.FROM_CONTEXT,
            explain = true,
        )!!
        assertEquals(listOf(b), explained.competing.map { it.supplier })
    }

    /** 2026-09-29: the same value reached through two equidistant ancestors is not an ambiguity. */
    @Test
    fun theSameValueTwiceIsNotAnAmbiguity() {
        val s = session("fc-same-value")
        val a = boxIn(s, "a")
        val b = boxIn(s, "b")
        val child = boxIn(s, "child")
        val shared = Token("shared")
        a.variable("renderer", shared)
        b.variable("renderer", shared)
        a.connect(child)
        b.connect(child)
        val found = ContextResolution.walk(
            child,
            ContextResolution.Query.byType(Token::class.java) { it is Token },
            ContextResolution.Policy.fromContext(false),
            ContextResolution.Mechanism.FROM_CONTEXT,
        )!!
        assertTrue(!found.ambiguous, "competing: ${found.competing.map { it.supplier }}")
    }

    // ---------------------------------------------------------------------------------------------
    // Inheritance (prop / rootProp)
    // ---------------------------------------------------------------------------------------------

    /** 2026-09-28, kept: properties only — a variable of the same name is invisible to it. */
    @Test
    fun inheritanceReadsPropertiesOnly() {
        val s = session("inh-props-only")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.variable("inhK", "variable")
        assertNull(child.inhK)

        parent.setPropertyRaw("inhK", "property")
        assertEquals("property", child.inhK)
    }

    /** 2026-09-28, kept: equidistant parents follow `parents` order here too. */
    @Test
    fun inheritanceEquidistantParentsFollowParentsOrder() {
        val s = session("inh-equidistant")
        val a = boxIn(s, "a")
        val b = boxIn(s, "b")
        val child = boxIn(s, "child")
        a.setPropertyRaw("inhK", "a")
        b.setPropertyRaw("inhK", "b")
        a.connect(child)
        b.connect(child)
        assertEquals("a", child.inhK)
        a.disconnect(child)
        a.connect(child)
        assertEquals("b", child.inhK)
    }

    // ---------------------------------------------------------------------------------------------
    // InheritanceFromContext (what @exported reads through)
    // ---------------------------------------------------------------------------------------------

    /** 2026-09-28, kept: per box, live variables are searched before properties. */
    @Test
    fun inheritanceFromContextPrefersAnAncestorsVariableOverItsProperty() {
        val s = session("ifc-vars-props")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.setPropertyRaw("_ifcY", "property")
        parent.variable("ifcY", "variable")

        assertEquals("variable", child._ifcY)
    }

    /**
     * 2026-09-28: the consumer's **own** variable called `ifcY` shadowed the parent's export — the
     * self-pinning shape `fromContext` was fixed for in bafa9ee5f: `val ifcY = _ifcY` resolved from
     * the parent once, then found itself on every later run.
     * FLIPPED 2026-09-29 (stdlib_behaviour_changes.md, "one context walk"): own variables count only
     * if they are exports, so the parent's value wins.
     */
    @Test
    fun inheritanceFromContextOwnVariableNoLongerShadowsTheExport() {
        val s = session("ifc-own-shadow")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.variable("ifcY", "parent")
        parent.setPropertyRaw("_ifcY", "parent")
        child.variable("ifcY", "child-leftover")

        assertEquals("parent", child._ifcY)
    }

    /** 2026-09-29: an own *property* still shadows — that is what writing `_ifcY = …` does. */
    @Test
    fun inheritanceFromContextOwnPropertyStillShadows() {
        val s = session("ifc-own-prop")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.variable("ifcY", "parent")
        child.setPropertyRaw("_ifcY", "local")

        assertEquals("local", child._ifcY)
    }

    /**
     * 2026-09-28, kept: the exporting box reads its own export — through its own variable, which is
     * allowed because `@exported` recorded it in `exportedVariables` (no property needed).
     */
    @Test
    fun inheritanceFromContextExporterReadsItsOwnExport() {
        val s = session("ifc-exporter")
        val exporter = boxIn(s, "exporter")
        exporter.variable("ifcY", "mine")
        exporter.ki.context.exportedVariables += "ifcY"

        assertEquals("mine", exporter._ifcY)
    }

    /** 2026-09-28, kept: a variable holding null still counts as found, and stops the walk. */
    @Test
    fun inheritanceFromContextNullVariableStopsTheWalk() {
        val s = session("ifc-null-var")
        val grand = boxIn(s, "grand")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        grand.connect(parent)
        parent.connect(child)
        grand.setPropertyRaw("_ifcY", "grand")
        parent.variable("ifcY", null)

        assertNull(child._ifcY)
    }

    /** 2026-09-28, kept: equidistant parents follow `parents` order. */
    @Test
    fun inheritanceFromContextEquidistantParentsFollowParentsOrder() {
        val s = session("ifc-equidistant")
        val a = boxIn(s, "a")
        val b = boxIn(s, "b")
        val child = boxIn(s, "child")
        a.variable("ifcY", "a")
        b.variable("ifcY", "b")
        a.connect(child)
        b.connect(child)
        assertEquals("a", child._ifcY)
        a.disconnect(child)
        a.connect(child)
        assertEquals("b", child._ifcY)
    }

    // ---------------------------------------------------------------------------------------------
    // The walker itself: what a Resolution says, beyond the value
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aResolutionNamesItsSupplierPathAndSlot() {
        val s = session("walk-path")
        val grand = boxIn(s, "grand")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        grand.connect(parent)
        parent.connect(child)
        grand.setPropertyRaw("inhK", "grand")

        val found = ContextResolution.walk(
            child,
            ContextResolution.Query.propertyNamed("inhK"),
            ContextResolution.Policy.INHERITANCE,
            ContextResolution.Mechanism.INHERITANCE,
        )!!
        assertEquals("grand", found.value)
        assertEquals(grand, found.supplier)
        assertEquals(listOf(child, parent, grand), found.path)
        assertEquals(ContextResolution.MatchKind.PROPERTY, found.matchKind)
        assertEquals("inhK", found.matchedName)
        assertEquals(2, found.depth)
        assertTrue(found.shadowed.isEmpty(), "an ordinary walk stops at the first match")
    }

    @Test
    fun anExplainWalkRecordsWhatLost() {
        val s = session("walk-explain")
        val grand = boxIn(s, "grand")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        grand.connect(parent)
        parent.connect(child)
        grand.setPropertyRaw("_ifcY", "grand")
        parent.variable("ifcY", "parent")

        val found = ContextResolution.walk(
            child,
            ContextResolution.Query("_ifcY", listOf("_ifcY", "ifcY"), { it != null }, { true }),
            ContextResolution.Policy.INHERITANCE_FROM_CONTEXT,
            ContextResolution.Mechanism.INHERITANCE_FROM_CONTEXT,
            explain = true,
        )!!
        assertEquals(parent, found.supplier)
        assertEquals(ContextResolution.MatchKind.VARIABLE, found.matchKind)
        assertEquals(listOf(grand), found.shadowed.map { it.supplier })
        assertEquals(listOf(child, parent, grand), found.shadowed.single().path)
    }

    // ---------------------------------------------------------------------------------------------
    // Preamble gathering
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // The Monaco hover
    // ---------------------------------------------------------------------------------------------

    /**
     * 2026-09-28: the hover matched only `var Box.x by …` and resolved every delegate as `Inheritance`
     * (raw properties only), so for an `@exported` `val` it said nothing, and for one supplied by a
     * variable it named whichever box had a *property* instead. FIXED 2026-09-29: it matches `val`, and
     * walks the way the declared delegate does.
     */
    @Test
    fun theHoverWalksTheWayTheDelegateDoes() {
        val s = session("hover")
        val grand = boxIn(s, "grand")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        grand.connect(parent)
        parent.connect(child)
        grand.setPropertyRaw("_x", 1)
        parent.variable("x", 2)

        val preamble = "val Box._x by InheritanceFromContext<(kotlin.Int) -> kotlin.Int>() /*from:parent*/ /**/"
        val hover = resolveInheritedDeclarationForHover(child, "_x", preamble)!!
        assertEquals("val", hover.keyword)
        assertEquals("(kotlin.Int) -> kotlin.Int", hover.declaredType)
        assertEquals(parent, hover.resolution!!.supplier, "the variable-first walk InheritanceFromContext does")
        assertEquals(listOf(child, parent), hover.resolution!!.path)
        assertEquals(listOf(grand), hover.resolution!!.shadowed.map { it.supplier })

        val asProp = resolveInheritedDeclarationForHover(child, "_x", "var Box._x by Inheritance<Int>() /**/")!!
        assertEquals(grand, asProp.resolution!!.supplier, "Inheritance reads properties only")
    }

    private val exportLine = "val Box._x by InheritanceFromContext<kotlin.Int>() /**/"

    private fun String.untagged() = lines().joinToString("\n") { stripPreambleSupplierTag(it) }

    /**
     * 2026-09-28, CURRENT then; kept 2026-09-29 for **untagged** lines only: an inherit-marked line
     * persisted into a child's own preamble before supplier tags existed outlives the edge that
     * brought it — nothing records where it came from, so nothing can say it is stale. Old documents
     * keep working exactly as they did.
     */
    @Test
    fun anUntaggedPersistedLineStillOutlivesTheDisconnect() {
        val s = session("pre-stale-legacy")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        val grandchild = boxIn(s, "grandchild")
        parent.connect(child)
        child.connect(grandchild)
        parent.setPropertyRaw("preamble", exportLine)
        // what checkPreambleAndExecute left in the child before 2026-09-29
        child.setPropertyRaw("preamble", exportLine)

        parent.disconnect(child)
        assertTrue(exportLine in gatherPreambleUpwards(child), gatherPreambleUpwards(child))
        assertTrue(exportLine in gatherPreambleUpwards(grandchild).untagged(), gatherPreambleUpwards(grandchild))
    }

    /**
     * 2026-09-29 (stdlib_behaviour_changes.md, "sourced preamble lines"): a line persisted with its
     * supplier's tag is dropped once the supplier is no longer an ancestor — from what the child
     * compiles, from what it would persist, and from what its children inherit.
     */
    @Test
    fun aTaggedPersistedLineIsDroppedAfterTheDisconnect() {
        val s = session("pre-stale-tagged")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        val grandchild = boxIn(s, "grandchild")
        parent.connect(child)
        child.connect(grandchild)
        parent.setPropertyRaw("preamble", exportLine)

        val inherited = gatherPreambleUpwards(child)
        val tagged = withPreambleSupplierTag(exportLine, "parent")
        assertTrue(tagged in inherited, "an inherited line comes down tagged with its supplier: $inherited")
        child.setPropertyRaw("preamble", tagged)
        assertTrue(tagged in gatherPreambleUpwards(grandchild), "and keeps that tag further down")

        parent.disconnect(child)
        val report = gatherPreambleUpwardsReport(child)
        assertTrue("Box._x" !in report.text, report.text)
        assertEquals(listOf(tagged), report.stale)
        assertTrue("Box._x" !in gatherPreambleUpwards(grandchild), gatherPreambleUpwards(grandchild))
        assertEquals("", dropStaleSuppliedLines(tagged, child))
    }

    /** 2026-09-29: an exporter's own `from:self` line is its own, and names it on the way down. */
    @Test
    fun aSelfTaggedLineNamesItsBoxOnTheWayDown() {
        val s = session("pre-self")
        val exporter = boxIn(s, "exporter")
        val child = boxIn(s, "child")
        exporter.connect(child)
        val own = withPreambleSupplierTag(exportLine, PREAMBLE_SELF_SUPPLIER)
        exporter.setPropertyRaw("preamble", own)

        assertTrue(own in gatherPreambleUpwards(exporter))
        assertEquals(own, dropStaleSuppliedLines(own, exporter))
        assertTrue(withPreambleSupplierTag(exportLine, "exporter") in gatherPreambleUpwards(child))
    }

    /**
     * 2026-09-28: two ancestors declaring `_x` with different types both landed, nearer first — so
     * in the REPL the *farther* declaration was executed last and shadowed the nearer one, while the
     * value came from the nearer. That is the ClassCastException the review describes.
     * FLIPPED 2026-09-29 (stdlib_behaviour_changes.md, "sourced preamble lines"): only the nearer
     * declaration lands, and the conflict is reported.
     */
    @Test
    fun conflictingInheritedDeclarationsKeepTheNearest() {
        val s = session("pre-conflict")
        val grand = boxIn(s, "grand")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        grand.connect(parent)
        parent.connect(child)
        val intLine = "val Box._x by InheritanceFromContext<kotlin.Int>() /**/"
        val stringLine = "val Box._x by InheritanceFromContext<kotlin.String>() /**/"
        parent.setPropertyRaw("preamble", intLine)
        grand.setPropertyRaw("preamble", stringLine)

        val report = gatherPreambleUpwardsReport(child)
        val text = report.text.untagged()
        assertTrue(intLine in text, text)
        assertTrue(stringLine !in text, text)
        val conflict = report.conflicts.single()
        assertEquals("Box._x", conflict.receiverAndName)
        assertEquals("parent", conflict.keptSupplier)
        assertEquals(listOf("grand"), conflict.discarded.map { it.second })
    }

    /** 2026-09-29: an identical declaration from two suppliers is not a conflict. */
    @Test
    fun identicalDeclarationsFromTwoSuppliersAreNotAConflict() {
        val s = session("pre-identical")
        val a = boxIn(s, "a")
        val b = boxIn(s, "b")
        val child = boxIn(s, "child")
        a.connect(child)
        b.connect(child)
        a.setPropertyRaw("preamble", exportLine)
        b.setPropertyRaw("preamble", exportLine)
        assertTrue(gatherPreambleUpwardsReport(child).conflicts.isEmpty())
    }

    /**
     * 2026-09-29: an untagged line of the box's own — usually a copy persisted from an ancestor before
     * tags existed — loses a conflict to a live ancestor's declaration, so an exporter that changed a
     * type is not overruled by a stale copy of its old one.
     */
    @Test
    fun anUntaggedOwnCopyLosesToALiveAncestor() {
        val s = session("pre-legacy-conflict")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        val newLine = "val Box._x by InheritanceFromContext<kotlin.String>() /**/"
        parent.setPropertyRaw("preamble", newLine)
        child.setPropertyRaw("preamble", exportLine)

        val report = gatherPreambleUpwardsReport(child)
        assertTrue(newLine in report.text.untagged(), report.text)
        assertTrue(exportLine !in report.text, report.text)
    }
}

private val fixtureDescriptor: Any? = null

private val Box.inhK: String? by Inheritance<String?>()
private val Box._ifcY: String? by InheritanceFromContext<String?>()
