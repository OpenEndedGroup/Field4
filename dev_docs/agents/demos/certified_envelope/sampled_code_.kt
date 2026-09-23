import afield.stdlib.Functions
import afield.stdlib.SignalChannel
import afield.stdlib.SignalViewer
import afield.stdlib.SignalViewerOptions
import afield.stdlib.displaySignal

// One hour: a slow 40-second swing, plus a glitch 10 ms wide at 1234.57 s — exactly halfway
// between two of the viewer's 0.02 s samples. Built from Functions ops rather than a
// fn { } lambda, so every node has a formula. Box 2 needs that.
val signal = with(Functions) {
    sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) +
        gaussian(1234.57, 0.002, 0.5, Functions.Domain.ALL)
}

// The sampled envelope, drawn the way the function workbench draws it: min and max per bin.
val viewer = SignalViewer(listOf(SignalChannel("x", signal::eval)), 0.0, 3600.0)
afield.Box.context.get()!!.displaySignal(viewer, "main",
    SignalViewerOptions(label = "sampled envelope only", viewKey = "certified_envelope:sampled"))

// The same query the plot makes, read back as numbers. Bin 342 holds the glitch.
val bins = viewer.window(0.0, 3600.0, 1000).lanes[0].current
val bin = (1234.57 / 3.6).toInt()
display("numbers", Table(listOf("what", "value"), title = "what the sampled plot can see")
    .row("f(1234.57), the glitch's peak", signal(1234.57))
    .row("sampled max, bin $bin", bins.high[bin])
    .row("sampled max, whole hour", bins.high.max())
    .row("missed by", signal(1234.57) - bins.high[bin])
    .role("value", CellRole.ACCENT))
