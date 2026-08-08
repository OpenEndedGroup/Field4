import afield.petri.*
import afield.four.synth.*

// Boxes 1-6 render *offline* — they mix a wav and hand you a player, which needs no
// audio device and so works in a docs build and in CI. This is the other path: the real
// polyphonic Dirt player, driven by the transport, tempo-synced and interruptible.
val root = DirtRender.dirtSampleRoot()
println(root?.let { "samples: $it" }
    ?: "no Dirt-Samples found — set ${DirtRender.ENV_VAR}, or install the SuperCollider quark")

val net = petri("""
    #petri
    !grid 1/8
    !conflict priority

    pulse : 1
    fill  : 0 ^3

    pulse  -[ a @1/8 pri=9 : bd ]-> mid
    mid    -[ b @1/8 pri=9 : hh ]-> pulse
    &mid   -[ build @1/8 pri=3      ]-> fill
    3*fill -[ crash @1/8 pri=1 : sd ]->
""")
showPetriNet(net, w = 700.0, h = 360.0)

// Flip to true to hear it. It is false by default because `audio { }` opens a real
// output device, and this transcript is replayed end to end in CI on machines that have
// none — everything below still compiles either way, which is the point of the flag
// rather than a comment.
val live = false

if (live && root != null) {
    val kit = dirtPlayer(library = dirt(root.toString()))
    audio { kit }
    net.emits().playDirt(kit, cps = 1.5)
    println("playing — stop the box to stop the sound")
} else {
    println("set `live = true` and run again to hear this through the speakers")
}
