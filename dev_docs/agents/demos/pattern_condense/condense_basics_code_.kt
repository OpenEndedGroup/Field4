import afield.four.synth.*

// 1 - Condense a rhythm.
//
// patCondense searches Field's own mini-notation for the shortest string that plays the
// rhythm you hand it, and it accepts a candidate only by playing it: every one goes back
// through pat(...) and its events are compared to the target's. So the lower lane of each
// pair below IS the string in its label, rendered. If a pair does not coincide, the search
// is wrong, and that is a thing to see rather than a thing to be told.
//
// POKE THIS: change a line in `rhythms`, or add one. A mask you invent comes back either as
// a named figure - x(p,s) for Euclidean, x(kN) / x(gN) for Kolakoski - or spelled out, and
// which one you get is decided by character count and nothing else.
val rhythms = listOf(
    "x ~ ~ x ~ x ~ ~",
    "x ~ ~ x x ~ x ~ ~ x ~ ~ x x ~ x",
    "bd [hh hh] sd [cp cp]",
    "a b c d e c a d"
)

val found = showPatCondense(*rhythms.toTypedArray())

val rows = rhythms.zip(found).joinToString("\n") { (source, c) ->
    "| `$source` | `${c.notation}` | ${c.notation.length}/${c.flat.length} | " +
        "${"%.2f".format(c.compression)} | ${c.structure.joinToString(", ")} |"
}

markdown(
    "main",
    """
    ### ${found.count { it.exact }} of ${found.size} condensed exactly

    | given | shortest string that plays it | chars | ratio | structure |
    |---|---|---|---|---|
    $rows

    The last row is why the other three mean anything. `a b c d e c a d` has no repeat at any
    split the grammar can take, so nothing shortens it and it comes back as itself. A search
    scored on description length has to be shown *not* naming a figure as well as naming one.

    Bounds this run used: ${found.first().limits}
    """.trimIndent()
)
