package afield.math.functions

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.path
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.execution.send
import afield.study.ui.numbers

import afield.math.autodiff.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `fnRewriteNumber { ... }` carries a live block and an empty `source`. A block that returns null
 * means "leave this numeric value alone"; it must not be mistaken for "there is no block", which
 * would send the expression down the source-hydration path and compile an empty lambda.
 */
class FunctionRewriteKotlinNullBlockTest {

    @Test
    fun blockReturningNullLeavesTheMatchedValueUnchanged() = with(Functions) {
        val source = sin(2.0) * 0.5

        var visited = 0
        val skipEverything = fnRewriteNumber {
            visited++
            null
        }

        val preview = source.previewRewrite(
            "**/[tag=number] => = @{null}",
            FnRewriteOptions(kotlinNumberContext = skipEverything)
        )

        assertTrue(preview.program.valid, "spec should compile: ${preview.program.diagnosticText()}")
        assertTrue(visited > 0, "the null-returning block should have been asked about at least one number")
        assertTrue(
            preview.applications.none { it.changed },
            "a null result means skip, so no application should report a change"
        )
        assertEquals(source.graphSignature(), preview.function.graphSignature())
        assertEquals(source.eval(0.37), preview.function.eval(0.37), 1e-12)
    }

    @Test
    fun blockReturningANumberStillRewrites() = with(Functions) {
        val source = sin(2.0) * 0.5

        val doubled = source.rewrite(
            "**/[tag=number] => = @{value * 2.0}",
            FnRewriteOptions(kotlinNumberContext = fnRewriteNumber { value * 2.0 })
        )

        // Positive control: the same selector really does reach numeric nodes, so the null case
        // above is a genuine skip rather than a spec that never matched anything.
        assertTrue(
            doubled.graphSignature() != source.graphSignature(),
            "a non-null result should rewrite the matched numbers"
        )
    }
}
