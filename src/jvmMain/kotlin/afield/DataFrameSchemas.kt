package afield

import afield.logger.lprintln
import org.jetbrains.kotlinx.dataframe.DataFrame

/**
 * Makes a DataFrame's columns nameable in *later* executions of the same box — the part the compiler
 * plugin cannot do on its own.
 *
 * The plugin types a refined call by the columns it produces, but only inside the expression that
 * produced it: the token it infers is a *local* class, which cannot appear in the type of a snippet's
 * property, so a frame left in a box comes back as `DataFrame<Any>` next time (measured in
 * [DataFramePluginSpike]). That is fatal for live coding, where the whole point is to load once and
 * then poke at the result.
 *
 * The way out is the one the Kotlin Jupyter kernel uses: stop asking the plugin to remember, and
 * generate a real named schema instead. [typed] reads the frame's **runtime** columns — which are
 * exactly right, because the data has already been loaded — and writes
 *
 * ```
 * @DataSchema interface People { val name: kotlin.String; val age: kotlin.Int }
 * ```
 *
 * into the calling box's preamble. From the next execution on, `People` is an ordinary source
 * declaration in that box, so the plugin's `TopLevelExtensionsGenerator` generates the column
 * accessors for it and `frame.cast<People>().age` resolves. Measured: a `@DataSchema` declared in an
 * earlier snippet *is* discovered in later ones; only a schema compiled into a directory is not.
 *
 * Three deliberate choices:
 *
 *  - **The preamble, not an inline eval.** We are executing inside this box's engine, and the compile
 *    dispatcher is a single thread, so evaluating another snippet from here risks deadlock. The
 *    preamble is the existing channel for "source this box should have compiled first", and
 *    `checkPreambleAndExecute` only evaluates lines that *changed* — so re-running a box with an
 *    unchanged schema costs nothing.
 *  - **One line, no `/**/`.** The preamble is line-oriented unless a block is marked, and a Kotlin
 *    interface fits on one line. Omitting the inheritance marker keeps the schema in this box rather
 *    than pushing it onto every child.
 *  - **Replace by name.** Re-running with a changed shape rewrites the declaration, which the
 *    preamble diff then re-executes. Unlike [SharedTypes], nothing here is loaded into a
 *    classloader, so redeclaring is ordinary REPL shadowing rather than an unrepeatable event.
 *
 * Flat value columns get their real element type. A column group or frame column would need a nested
 * interface to type properly, so it is declared as `Any?` — you still get the column, just not its
 * shape — and the log names which ones landed that way.
 *
 * @param name the interface to declare; also what you cast to.
 * @return this frame, unchanged, so the call can sit in the middle of a chain.
 */
fun <T> DataFrame<T>.typed(name: String): DataFrame<T> {
    require(name.isNotBlank()) { "typed(name) needs a schema name" }
    require(name.first().isLetter() || name.first() == '_') {
        "'$name' is not a usable Kotlin interface name"
    }

    val box = Box.context.get()
    if (box == null) {
        lprintln("warning,dataframe", "typed(\"$name\"): no box in context, so nothing was published")
        return this
    }

    val loader = KotlinInterface2.sharedClassLoader ?: javaClass.classLoader
    // `render` never returns null: a type it cannot name in a consuming box comes back as
    // ExportedTypeNames.OPAQUE ("Any?"), which is still a legal property type and still worth
    // declaring — you get the column, just not its element type. A column GROUP lands here, since
    // typing one properly needs a nested interface.
    val rendered = columns().map { it.name() to ExportedTypeNames.render(it.type(), loader) }
    val opaque = rendered.filter { it.second == ExportedTypeNames.OPAQUE }.map { it.first }
    val properties = rendered.map { (columnName, type) -> "val `" + columnName + "`: " + type }

    if (properties.isEmpty()) {
        lprintln("warning,dataframe", "typed(\"$name\"): no nameable columns; nothing was published")
        return this
    }

    val declaration =
        "@org.jetbrains.kotlinx.dataframe.annotations.DataSchema " +
            "interface $name { ${properties.joinToString("; ")} }"

    publishToPreamble(box, name, declaration)

    val where = box.name.ifBlank { box.id }
    val note = if (opaque.isEmpty()) "" else " (untyped as Any?: " + opaque.joinToString(", ") + ")"
    lprintln(
        "info,dataframe",
        "typed(\"" + name + "\"): " + properties.size + " column(s) published to '" + where +
            "' — from the next run of this box, cast to " + name + note
    )
    return this
}

/**
 * Put [declaration] in [box]'s preamble, replacing any previous declaration of the same interface.
 *
 * Matching is on the `interface <name> {` prefix rather than the whole line, which is what makes a
 * re-run with a *changed* set of columns replace the old shape instead of accumulating a second
 * declaration of the same name.
 */
private fun publishToPreamble(box: Box, name: String, declaration: String) {
    val marker = "interface $name {"
    val kept = box.preamble
        .lines()
        .filter { it.isNotBlank() && !it.contains(marker) }
    if (kept.size == box.preamble.lines().count { it.isNotBlank() } &&
        box.preamble.contains(declaration)
    ) {
        return // already present and unchanged
    }
    box.preamble = (kept + declaration).joinToString("\n")
}
