import afield.stdlib.Functions
import afield.stdlib.SignalViewer
import afield.stdlib.SignalViewerOptions
import afield.stdlib.certifiedEnvelope
import afield.stdlib.certifiedSignalChannel
import afield.stdlib.displaySignal

// The same signal as box 1. certifiedSignalChannel draws its sampled envelope in white and,
// behind it in amber, a certified bound per bin: every value the function takes in the bin
// lies inside, whether or not a sample landed on it. Nothing here is sampled more finely.
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) +
        gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}
val viewer = SignalViewer(listOf(certifiedSignalChannel("x", signal)), 0.0, 3600.0)
displaySignal(viewer, "main",
    SignalViewerOptions(label = "sampled envelope, with its certified band", viewKey = "certified_envelope:certified"))

// The band, read back: bin 342 is bounded above by the glitch's true height.
val band = signal.certifiedEnvelope(0.0, 3600.0, 1000)
val bin = (1234.57 / 3.6).toInt()
display("numbers", Table(listOf("what", "value"), title = "the certified band, as numbers")
    .row("f(1234.57), the glitch's peak", signal(1234.57))
    .row("certified max, bin $bin", band.high[bin])
    .row("certified min, bin $bin", band.low[bin])
    .row("enclosures for 1000 bins", band.enclosures)
    .role("value", CellRole.ACCENT))
