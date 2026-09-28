package afield

import afield.four.modules.fromContext
import afield.four.modules.fromContextWithNameOrNull
import afield.macros.gatherPreambleUpwards
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
     * 2026-09-28, CURRENT: two same-typed variables in one ancestor resolve in `HashMap` iteration
     * order, which is neither declaration order nor anything a person can see. `early` is declared
     * first and lands in a later hash bucket than `late`, so the two orders disagree — asserted, so
     * this fence cannot silently become a tautology.
     */
    @Test
    fun fromContextVariableTieWithinOneBoxFollowsHashOrder() {
        val s = session("fc-var-tie")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.variable("early", Token("early"))
        parent.variable("late", Token("late"))

        val hashOrderFirst = HashMap(parent.ki.context.vars).values.first().value as Token
        assertNotEquals("early", hashOrderFirst.label, "fixture premise: hash order must differ from declaration order")
        assertEquals(hashOrderFirst.label, child.reading { fromContext<Token>() }.label)
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
     * 2026-09-28, CURRENT: the consumer's **own** variable called `ifcY` shadows the parent's export.
     * This is the self-pinning shape `fromContext` was fixed for in bafa9ee5f, never fixed here:
     * `val ifcY = _ifcY` resolves from the parent once, then finds itself on every later run.
     */
    @Test
    fun inheritanceFromContextOwnVariableShadowsTheExport() {
        val s = session("ifc-own-shadow")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.variable("ifcY", "parent")
        parent.setPropertyRaw("_ifcY", "parent")
        child.variable("ifcY", "child-leftover")

        assertEquals("child-leftover", child._ifcY)
    }

    /** 2026-09-28, kept: the exporting box reads its own export. */
    @Test
    fun inheritanceFromContextExporterReadsItsOwnExport() {
        val s = session("ifc-exporter")
        val exporter = boxIn(s, "exporter")
        exporter.variable("ifcY", "mine")
        exporter.setPropertyRaw("_ifcY", "mine")

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

    private val exportLine = "val Box._x by InheritanceFromContext<kotlin.Int>() /**/"

    /**
     * 2026-09-28, CURRENT: an inherit-marked line persisted into a child's own preamble outlives the edge that
     * brought it. After the parent is disconnected, the child still compiles it and hands it on to
     * its own children, because it still ends in the inherit marker.
     */
    @Test
    fun aPersistedInheritedLineOutlivesTheDisconnect() {
        val s = session("pre-stale")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        val grandchild = boxIn(s, "grandchild")
        parent.connect(child)
        child.connect(grandchild)
        parent.setPropertyRaw("preamble", exportLine)
        // what checkPreambleAndExecute leaves in the child after one run
        child.setPropertyRaw("preamble", exportLine)

        parent.disconnect(child)
        assertTrue(exportLine in gatherPreambleUpwards(child), gatherPreambleUpwards(child))
        assertTrue(exportLine in gatherPreambleUpwards(grandchild), gatherPreambleUpwards(grandchild))
    }

    /**
     * 2026-09-28, CURRENT: two ancestors declaring `_x` with different types both land, nearer first —
     * so in the REPL the *farther* declaration is executed last and shadows the nearer one, while the
     * value comes from the nearer. That is the ClassCastException the review describes.
     */
    @Test
    fun conflictingInheritedDeclarationsBothLandFarthestLast() {
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

        val gathered = gatherPreambleUpwards(child)
        val intAt = gathered.indexOf(intLine)
        val stringAt = gathered.indexOf(stringLine)
        assertTrue(intAt >= 0 && stringAt >= 0, gathered)
        assertTrue(intAt < stringAt, "nearer declaration first, farther last: $gathered")
    }
}

private val fixtureDescriptor: Any? = null

private val Box.inhK: String? by Inheritance<String?>()
private val Box._ifcY: String? by InheritanceFromContext<String?>()
