import afield.stdlib.Functions
import afield.stdlib.certifiedNeverExceeds
import afield.stdlib.certifiedRange
import afield.stdlib.certifiedReaches

// Branch-and-bound over the hour: the true maximum is at least the value reached at the
// witness, and at most the proven bound. The same for the minimum.
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) +
        gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}
val range = signal.certifiedRange(0.0, 3600.0, tolerance = 1e-9)
val max = range.max!!
val min = range.min!!
display("range", Table(listOf("side", "reached", "at x", "proven bound", "enclosures"),
    title = "certifiedRange(0, 3600): certified = ${range.certified}")
    .row("max", max.value, max.witness, max.bound, max.enclosures)
    .row("min", min.value, min.witness, min.bound, min.enclosures)
    .role("at x", CellRole.ACCENT))

// Yes/no questions with the same guarantee. A refutation carries the x that breaks the claim.
val claims = listOf(
    signal.certifiedNeverExceeds(0.7, 0.0, 3600.0),
    signal.certifiedNeverExceeds(0.8, 0.0, 3600.0),
    signal.certifiedReaches(0.75, 0.0, 3600.0),
)
display("claims", Table(listOf("claim", "verdict", "witness x"))
    .rows(claims.map { listOf(it.claim, it.kind.name, it.witness) })
    .roleBy("verdict") { if (it == "PROVEN") CellRole.OK else CellRole.WARN })
