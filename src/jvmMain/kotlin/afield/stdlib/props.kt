package afield.stdlib

import afield.Box
import afield.kotlinImportStatementFor
import afield.logger.lprintln
import afield.macros.Insert
import afield.macros.MacroRegistry


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
        here = "$name", preamble = kotlinImportStatementFor(contents[0].replace("<", "").replace(">", "").trim())?: "", preambleAfterImports = """
        var Box.${name} by Inheritance${contents[0]}()
        val Once.${name} by Onceing${contents[0]}()
    """.trimIndent()
    )
}

fun Box.macro_root_prop(contents: List<String>): Insert? {
    if (contents.size != 2)
        throw MacroRegistry.MacroExpansionProblem("rootProp should be called like rootProp<Int>(foo)")

    val name = contents[1].trim().removeSurrounding("(", ")")
    return Insert(
        here = "root.$name", preamble = kotlinImportStatementFor(contents[0].replace("<", "").replace(">", "").trim())?: "", preambleAfterImports = """
        var Box.${name} by Inheritance${contents[0]}()
        val Once.${name} by Onceing${contents[0]}()
    """.trimIndent(), target = this.root
    )
}
