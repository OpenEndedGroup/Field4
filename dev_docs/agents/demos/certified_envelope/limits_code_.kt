import afield.stdlib.Functions
import afield.stdlib.certifiedEquals
import afield.stdlib.certifiedNeverExceeds
import afield.stdlib.enclose

// What refuses. The function workbench's channels are fn(0.0..3600.0, name) { seconds -> ... }
// lambdas: one opaque node each, so there is nothing for an interval walk to read. It refuses by
// name rather than guess.
val lambdaChannel = with(Functions) { fn(0.0..3600.0, "x") { t -> 0.45 + 0.2 * kotlin.math.sin(t) } }
val refused = lambdaChannel.enclose(0.0, 3600.0)

// The workbench's claim that an event holds exactly 1 for 30 seconds, rebuilt from formula ops:
// rise 185..285, hold to 315, fall to 415, over the moving background.
val held = with(Functions) {
    val one = c(1.0, Functions.Domain.ALL)
    val background = sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45)
    val envelope = smoothStep(185.0, 285.0, Functions.Domain.ALL) *
        (one - smoothStep(315.0, 415.0, Functions.Domain.ALL))
    one - (one - background) * (one - envelope)
}
val checks = listOf(
    held.certifiedEquals(1.0, 285.0, 315.0),
    held.certifiedEquals(1.0, 284.0, 315.0),
    held.certifiedNeverExceeds(1.0, 0.0, 3600.0, slack = 1e-12),
)
display("limits", Table(listOf("question", "answer"), title = "refusals and exact holds")
    .row("enclose the lambda channel", "certified = ${refused.certified}: ${refused.refusal}")
    .rows(checks.map { listOf(it.claim, it.kind.name + (it.witness?.let { w -> " at x = $w" } ?: "")) })
    .role("answer", CellRole.ACCENT))
