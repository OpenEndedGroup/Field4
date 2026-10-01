package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The anti-rot guard for differentiability coverage: **every** op `OpRegistry` knows must either have
 * a formula in [FnFormulas] or an explicit, reasoned entry in [FnNonDifferentiable].
 *
 * Without this, the coverage story decays quietly — someone adds an op to `Functions` and
 * `OpRegistry`, the exact-gradient path refuses it at runtime with a generic message, and under
 * `AUTO` the fit silently drops back to finite differences and nobody notices. With it, the *build*
 * asks for a decision: write the derivative, or say in one line why there is not one.
 *
 * It also prints the coverage fraction, so the number in `dev_docs/autodiff.md` can be checked rather
 * than trusted.
 */
class FnOpCoverageTest {

    /** Every op `OpRegistry` knows, with aliases resolved to their canonical spelling, lower-cased. */
    private fun canonicalOps(): Set<String> =
        OpRegistry.knownOps.map { OpRegistry.canonical(it)?.lowercase() ?: it.lowercase() }.toSortedSet()

    @Test
    fun everyRegistryOpIsEitherDifferentiableOrDeclaredNotToBe() {
        val canonical = canonicalOps()
        val differentiable = FnAutoDiff.differentiableOps
        val declared = FnNonDifferentiable.ops

        val unclassified = canonical.filterNot { it in differentiable || it in declared }
        if (unclassified.isNotEmpty()) {
            fail(
                "these ops are in OpRegistry but classified neither differentiable nor not: " +
                    "$unclassified\n" +
                    "Add an FnExpr to FnFormulas, or an entry to FnNonDifferentiable saying in one " +
                    "line why there is no derivative."
            )
        }

        val closable = FnNonDifferentiable.closable
        println(
            "OP COVERAGE: ${differentiable.size} of ${canonical.size} canonical ops differentiable " +
                "(${"%.1f".format(100.0 * differentiable.size / canonical.size)}%), " +
                "${declared.size} declared non-differentiable, ${closable.size} of those closable"
        )
        println("CLOSABLE: " + closable.joinToString(" "))
    }

    @Test
    fun noOpIsBothDifferentiableAndDeclaredNotToBe() {
        val both = FnAutoDiff.differentiableOps.filter { it in FnNonDifferentiable.ops }
        assertTrue(both.isEmpty(), "these ops are classified twice, contradictorily: $both")
    }

    @Test
    fun everyDeclaredNonDifferentiableOpIsActuallyKnownToTheRegistry() {
        // A typo'd entry would silently satisfy the coverage test above while classifying nothing.
        val unknown = FnNonDifferentiable.ops.filterNot { OpRegistry.knows(it) }
        assertTrue(unknown.isEmpty(), "FnNonDifferentiable names ops OpRegistry has never heard of: $unknown")
    }

    @Test
    fun everyDeclaredNonDifferentiableOpHasARealReasonAndAClosablePlan() {
        for (op in FnNonDifferentiable.ops) {
            val status = FnNonDifferentiable.statusOf(op)!!
            assertTrue(status.reason.length > 30, "$op: reason is too thin to be a reason: '${status.reason}'")
            if (status.closable) {
                assertTrue(
                    status.missing.length > 20,
                    "$op: declared closable but does not say what is missing: '${status.missing}'"
                )
            } else {
                assertEquals("", status.missing, "$op: declared not closable but names something missing")
            }
        }
    }

    @Test
    fun explainAnswersForEveryOpInBothCamps() {
        assertTrue("differentiable" in FnNonDifferentiable.explain("sin"))
        assertTrue("hash of position" in FnNonDifferentiable.explain("whiteNoise"))
        assertTrue("closable" in FnNonDifferentiable.explain("pulseburst"))
        assertTrue("not classified" in FnNonDifferentiable.explain("definitelyNotAnOp"))
        // Aliases resolve too, since OpRegistry.knows is alias-aware and statusOf lower-cases.
        assertTrue("hash of position" in FnNonDifferentiable.explain("whitenoise"))
    }

    /**
     * The refusal diagnostic a user actually sees should name the offending op *and* say why, not just
     * that it is unsupported — that is the difference between a dead end and a decision.
     */
    @Test
    fun theRefusalDiagnosticExplainsRatherThanJustRefusing() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8)
        val refusal = exactFitRefusal(target, valueNoise(3L, 2.0, 0.9), Functions.Domain(0.0, 1.0))
        assertTrue(refusal != null, "a valueNoise seed must be refused")
        assertTrue("valuenoise" in refusal.lowercase(), "the refusal should name the op: $refusal")
    }
}
