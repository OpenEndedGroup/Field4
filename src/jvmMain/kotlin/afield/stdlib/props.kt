@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.kotlinImportStatementFor
import afield.logger.lprintln
import afield.macros.Insert
import afield.macros.MacroRegistry

/**
 * The `prop` / `rootProp` macro pair — the typed, inheritable box-property slot.
 *
 * [prop] and [rootProp] exist only as the declarations that make `prop<Int>(foo)` resolve; neither
 * is meant to run, and calling one throws `MacroRegistry.MacroNotExpandedException`. The real work
 * happens at expansion time in [macro_prop] and [macro_root_prop], which are handed the type
 * argument and the name, and return an `Insert` that rewrites the call site to the bare name (or
 * `root.name`) and pushes two declarations into the box's preamble: a
 * `var Box.<name> by Inheritance<T>()` and a `val Once.<name> by Onceing<T>()`. Both generated
 * lines carry the trailing empty-comment marker that makes the preamble gather copy them down into
 * child boxes, and an import for `T` is added via `kotlinImportStatementFor`. [macro_root_prop]
 * differs only in targeting `this.root`, so the accessors land on the document root rather than
 * on the calling box.
 */

/**
 * Declares a typed, inheritable property on this box: `prop<Double>(speed)` and thereafter `speed` is a
 * variable that children inherit and the property inspector can see.
 *
 * The one-liner that replaces writing the delegate out by hand. Expansion rewrites the call site to the bare
 * name and adds `var Box.speed by Inheritance<Double>()` to the box's preamble, so reading it walks up the
 * parents until some ancestor has a value and writing it shadows locally. The name is written **unquoted** —
 * it is a name, not a string.
 *
 * A **macro placeholder**: it throws `MacroRegistry.MacroNotExpandedException` if it is ever really called,
 * which is what you see if the macro registry did not get a chance to rewrite the call.
 */
fun <T> Box.prop(name: String) {
    throw MacroRegistry.MacroNotExpandedException("prop should not be callable directly, but should be expanded as a macro")
}

/**
 * [prop], but the accessors land on the **document root** rather than on this box — so every box in the
 * document shares the one slot, wherever it is read from.
 *
 * Use it for document-wide settings, where `prop` would give each subtree its own shadowable copy. The call
 * site becomes `root.name`, so it does not matter where in the graph the declaring box sits.
 *
 * A macro placeholder, and throws `MacroNotExpandedException` if actually called.
 */
fun <T> Box.rootProp(name: String) {
    throw MacroRegistry.MacroNotExpandedException("prop should not be callable directly, but should be expanded as a macro")
}

/**
 * Macro expansion for `prop<T>(name)`: rewrites the call to the bare name and pushes the delegate declarations
 * into the box's preamble.
 *
 * Emits both a `var Box.<name> by Inheritance<T>()` and a `val Once.<name> by Onceing<T>()`, each carrying the
 * trailing empty-comment marker that makes the preamble gather copy it down into child boxes, plus an import
 * for `T`. Called by `MacroRegistry`, not by you.
 *
 * @throws afield.macros.MacroRegistry.MacroExpansionProblem unless given exactly two pieces — the type argument
 * and the name — which is what a malformed `prop(foo)` without a type looks like from here
 */
fun Box.macro_prop(contents: List<String>): Insert? {
    if (contents.size != 2)
        throw MacroRegistry.MacroExpansionProblem("prop should be called like prop<Int>(foo)")

    val name = contents[1].trim().removeSurrounding("(", ")")
    return Insert(
        here = "$name", standaloneHere = "Unit",
        preamble = kotlinImportStatementFor(contents[0].replace("<", "").replace(">", "").trim())?: "", preambleAfterImports = """
        var Box.${name} by Inheritance${contents[0]}() /**/
        val Once.${name} by Onceing${contents[0]}() /**/
    """.trimIndent()
    )
}

/**
 * Macro expansion for `rootProp<T>(name)` — the same declarations as [macro_prop], but the `Insert` targets
 * `this.root` and the call site becomes `root.<name>`.
 *
 * @throws afield.macros.MacroRegistry.MacroExpansionProblem unless given exactly the type argument and the name
 */
fun Box.macro_root_prop(contents: List<String>): Insert? {
    if (contents.size != 2)
        throw MacroRegistry.MacroExpansionProblem("rootProp should be called like rootProp<Int>(foo)")

    val name = contents[1].trim().removeSurrounding("(", ")")
    return Insert(
        here = "root.$name", standaloneHere = "Unit",
        preamble = kotlinImportStatementFor(contents[0].replace("<", "").replace(">", "").trim())?: "", preambleAfterImports = """
        var Box.${name} by Inheritance${contents[0]}() /**/
        val Once.${name} by Onceing${contents[0]}() /**/
    """.trimIndent(), target = this.root
    )
}
