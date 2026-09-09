// WORKS. `rewriteFn` names the box to compile the `@{...}` snippets in, and that box is
// idle, so hydrating there replays nothing. One pass, and the sampled signal really moves.
import afield.stdlib.Functions
import afield.stdlib.rewriteFn

val spec = listOf(
    "#fnrewrite",
    "sin/*/saw => op=triangle",
    "sin/**/[tag=number][value>0.15] => *= @{1.0 + depth * 0.08}",
    "**/[role=amplitude] => *= @{if (parentOp == \"sin\") 0.75 else 1.0}"
).joinToString("\n")

// The host comes from TOPOLOGY, not from a hard-coded id: whatever this box is connected
// beneath is where the `@{...}` snippets get compiled. Drag a different parent in and the
// rewrite follows it.
//
// NOT `?: error(...)`. Inside a box, `error` is `HasOutput.error(message, property, line)`,
// which returns Unit, so the elvis widens this to `Any` and the failure surfaces as a
// baffling "receiver type mismatch" on rewriteFn below. Throw explicitly.
val host = parents.lastOrNull()
    ?: throw IllegalStateException("connect this box under a box to compile the @{...} in")
println("compiling in parent: " + host.name)

with(Functions) {
    val base = (sin(sin(saw(0.18))) * 0.45) over (0.0..3.0)
    val changed = host.rewriteFn(base, spec)
    println("before  f(1.0) = " + base.eval(1.0))
    println("after   f(1.0) = " + changed.eval(1.0))
    base.withName("before").plot(0.0..3.0, channel = "before", heightPx = 200, title = "before rewrite")
    changed.withName("after").plot(0.0..3.0, channel = "after", heightPx = 200, title = "after rewrite")
}
