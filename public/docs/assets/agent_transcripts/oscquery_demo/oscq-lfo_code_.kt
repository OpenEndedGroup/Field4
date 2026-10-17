import afield.io.osc.query.*

// A value Field changes by itself: every listener (box 4's slider, TouchDesigner) follows it.
val q = oscQuery("Field demo")
val lfo = q.float("/demo/lfo", 0.0..1.0, 0.5, "a slow sine, written by Field", access = OscAccess.READ)
val start = AnimationClock.nowSeconds()
loop {
    lfo.set(0.5 + 0.5 * sin(AnimationClock.nowSeconds() - start))
    delay(50)
}
