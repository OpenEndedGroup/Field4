package afield.macros

import afield.FieldKtParser
import afield.RunsAt
import afield.macroRegistration
import afield.macros.MacroRegistry.callRewrite
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Labels a function's positional arguments with their own source text, before the box compiles.
 *
 * A function whose last parameter is `vararg panels: Pair<String, T>` wants a label for every value,
 * and the label is nearly always the name the box already gave it. Annotated with this, the call
 *
 * ```kotlin
 * showMeshGrid(cube, ball.subdivided(2), "the torus" to ring, columns = 3)
 * ```
 *
 * is rewritten to
 *
 * ```kotlin
 * showMeshGrid("cube" to cube, "ball.subdivided(2)" to ball.subdivided(2), "the torus" to ring, columns = 3)
 * ```
 *
 * Arguments that already carry a label (`"..." to x`), string literals, named arguments and spreads
 * are left alone, so an explicit label always wins. The rewrite is by name, like every Field macro: it
 * applies to any call of a function of that name in box code.
 *
 * Found by the same classpath scan that finds [RunsAt] functions, so annotating a function is all it
 * takes; there is no list to keep in step.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LabelsArgumentsFromSource

/** Registers a [labelArgumentsFromSource] rewrite for every function annotated [LabelsArgumentsFromSource]. */
@RunsAt(macroRegistration)
fun registerLabelledArgumentMacros() {
    afield.labelledArgumentFunctions.forEach { name ->
        callRewrite[name] = { _, id, args -> labelArgumentsFromSource(id, args) }
    }
}

/**
 * The call [callee]`(`[arguments]`)` with each unlabelled positional argument written as
 * `"<its source>" to <it>`, or null when every argument is already labelled (which is also what ends
 * the expander's re-run loop on the rewritten call).
 *
 * @param arguments the source text after the callee, as the expander hands it over: the parenthesised
 * argument list first, then any trailing lambda
 */
fun labelArgumentsFromSource(callee: String, arguments: List<String>): Insert? {
    val listIndex = arguments.indexOfFirst { it.trimStart().startsWith("(") }
    if (listIndex < 0) return null
    val call = try {
        // The outermost call: descendants come innermost first, and an argument may be a call itself.
        FieldKtParser.parse("$callee${arguments[listIndex]}").collectDescendantsOfType<KtCallExpression>()
            .filter { it.calleeExpression?.text == callee }.minByOrNull { it.textRange.startOffset }
    } catch (t: Throwable) {
        null
    } ?: return null

    var changed = false
    val rewritten = call.valueArguments.map { argument ->
        val expression = argument.getArgumentExpression()
        val labelled = argument.isNamed() || argument.getSpreadElement() != null || expression == null ||
            expression is KtStringTemplateExpression ||
            (expression is KtBinaryExpression && expression.operationReference.text == "to")
        if (labelled) argument.text
        else {
            changed = true
            "${kotlinStringLiteral(expression.text.replace(Regex("""\s+"""), " "))} to ${expression.text}"
        }
    }
    if (!changed) return null
    val rest = arguments.drop(listIndex + 1).joinToString("")
    return Insert(here = "$callee(${rewritten.joinToString(", ")})$rest")
}

private fun kotlinStringLiteral(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
