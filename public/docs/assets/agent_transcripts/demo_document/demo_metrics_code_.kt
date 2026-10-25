// Open this box's editor, then launch it: the three calls draw beside their own lines.
var tick = 0.0
var visualizerFrame = 0

println("inline metrics ready")

loop {
    tick += 0.4
    visualizerFrame += 1

    if (visualizerFrame % 2 == 0) {
        val value = sin(tick * 0.22) * 0.7 + cos(tick * 0.11) * 0.2 + tick * 0.01

        __m("histogram").histogram(value, dur = 120)
        __m("trail").history(value, dur = 48)
        __m("summary").stats(value, dur = 48)
    }
    frame()
}
