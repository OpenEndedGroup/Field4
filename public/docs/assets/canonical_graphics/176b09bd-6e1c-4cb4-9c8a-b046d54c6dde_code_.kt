import afield.stdlib.*

// The same model with a field probe on the cars that move. `probe` is a GLSL expression
// recorded once per moving car per half-step, here the x + y of the cell it leaves.
// The card's `calls` is how many cars moved; divide by density * 512 * 512 / 2 for the
// mean velocity.
var bml = ShaderBmlSystem() {
    name = "bml-probe"
    width = 512
    height = 512
    density = 0.32f
    probe = "float(p.x + p.y)"
}
bml.installInto()

fieldProbeHere(samples = 4096).display("probe")
displayOpen("probe")
