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

fun <T> Box.prop(name: String) {
    throw MacroRegistry.MacroNotExpandedException("prop should not be callable directly, but should be expanded as a macro")
}

fun <T> Box.rootProp(name: String) {
    throw MacroRegistry.MacroNotExpandedException("prop should not be callable directly, but should be expanded as a macro")
}

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
