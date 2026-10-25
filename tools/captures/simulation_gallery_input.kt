// Run in a child of sim_fluid after four_simulation_chain has launched.
// This supplies a continuous drag to the real GPU fluid; it draws no pixels itself.
import afield.runtime.lifecycle.frames
import kotlin.math.sin
import kotlin.math.cos

frames {
    var t = 0.0
    fluid["splatCenter"] = { vec(0.42 + 0.20 * cos(t), 0.50 + 0.20 * sin(t)) }
    fluid["splatVelocity"] = { vec(-sin(t) * 180.0, cos(t) * 180.0) }
    fluid["splatMass"] = 0.08f
    fluid["splatRadius"] = 0.035f
    while (true) {
        t += 0.025
        frame()
    }
}
