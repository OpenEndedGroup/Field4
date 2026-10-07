package afield.macros

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [labelArgumentsFromSource]: what it labels, what it leaves alone, and when it stops. */
class LabelledArgumentsTest {
    private fun label(argumentList: String, vararg rest: String) =
        labelArgumentsFromSource("showMeshGrid", listOf(argumentList, *rest))?.here

    @Test
    fun bareNamesAndExpressionsAreLabelledWithTheirSource() {
        assertEquals(
            "showMeshGrid(\"cube\" to cube, \"ball.subdivided(2)\" to ball.subdivided(2))",
            label("(cube, ball.subdivided(2))"),
        )
    }

    @Test
    fun explicitLabelsNamedArgumentsAndSpreadsAreLeftAlone() {
        assertEquals(
            "showMeshGrid(\"a torus\" to ring, \"cube\" to cube, columns = 3, *more)",
            label("(\"a torus\" to ring, cube, columns = 3, *more)"),
        )
    }

    @Test
    fun aFullyLabelledCallIsNotRewrittenSoTheExpanderStops() {
        assertNull(label("(\"cube\" to cube, columns = 3)"))
    }

    @Test
    fun aMultiLineArgumentGetsAOneLineLabelAndQuotesAreEscaped() {
        assertEquals(
            "showMeshGrid(\"named(\\\"a\\\") .x\" to named(\"a\")\n    .x)",
            label("(named(\"a\")\n    .x)"),
        )
    }

    @Test
    fun aDollarInTheSourceDoesNotBecomeATemplate() {
        // `lookup("$")` labelled as the Kotlin literal "lookup(\"\$\")": the `$` escaped, not a template.
        assertEquals("showMeshGrid(\"lookup(\\\"\\\$\\\")\" to lookup(\"\$\"))", label("(lookup(\"\$\"))"))
    }

    @Test
    fun aTrailingLambdaIsKept() {
        assertEquals("showMeshGrid(\"cube\" to cube) { it }", label("(cube)", " ", "{ it }"))
    }
}
