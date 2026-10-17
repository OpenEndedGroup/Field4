import afield.audio.synth.PatArc
import afield.audio.synth.PatQ
import org.lwjgl.vulkan.VK10.*

val cps = 0.5     // cycles a second; the net's grid is an eighth of a cycle
val life = 1.6    // seconds a spark lives, LIFE in the vertex shader
val slots = 24    // SLOTS in the vertex shader: firings drawn at once
val particles = slots * 1400 + 3 * 2400

// The rings as the shader draws them, centre out, and where each sounding transition
// launches from: (colour, ring, place). The clap launches from the centre.
val rings = listOf(listOf("s0", "s1", "s2"), listOf("k0", "k1", "k2", "k3"), listOf("h0", "h1", "h2", "h3", "h4"))
val radius = listOf(0.36, 0.56, 0.76)
val launches = mapOf(
    "kick" to Triple(0, 1, 0),
    "snare" to Triple(1, 0, 0),
    "hat" to Triple(2, 2, 0),
    "hat.2" to Triple(3, 2, 2),
    "clap" to Triple(4, -1, 0),
)

// A place's angle on its ring, the token going clockwise. Each ring's first place is a
// third of a turn round from the last one's, so the three launch points spread out.
fun angle(ring: Int, place: Double) = Math.PI / 2 - 2 * Math.PI * (ring / 3.0 + place / rings[ring].size)

var module = moduleHere {
    primitiveType = triangles
    blend = true
    srcColor = VK_BLEND_FACTOR_ONE
    dstColor = VK_BLEND_FACTOR_ONE
    depthTest = false
    depthWrite = false
}
var sparks = Mesh()
module += "sparks" to sparks
renderer += "petri-sparks" to module

// perFrame: one buffer per frame in flight, so it can be rewritten every frame.
var bursts = defaultBuffer {
    perFrame = true
    size = slots.vec4s
}.buffer

var charge = 0.0
val tokens = DoubleArray(3)

sparks.bursts = { bursts }
sparks.aspectRatio = { window.width / window.height.toFloat() }
sparks.charge = { charge.f }
sparks.token0 = { angle(0, tokens[0]).f }
sparks.token1 = { angle(1, tokens[1]).f }
sparks.token2 = { angle(2, tokens[2]).f }
sparks.set(particles * 6, 0) {}

println("sparks ready: $particles particles, one burst per firing of ${launches.keys}")

var frame = 0
loop {
    // Where the net is, in cycles, counting frames at sixty a second.
    val now = ++frame / 60.0 * cps
    val at = PatQ.fromDouble(now)

    // Everything that fired in the last `life` seconds, newest last.
    val since = PatQ.fromDouble(maxOf(0.0, now - life * cps))
    val recent = sparkRun.firingsOver(PatArc(since, at))
        .filter { it.transition in launches }
        .takeLast(slots)

    bursts.write {
        for (s in 0 until slots) {
            val firing = recent.getOrNull(s)
            if (firing == null) {
                put(vec(0.0, 0.0, -1.0, 0.0))
            } else {
                val (colour, ring, place) = launches.getValue(firing.transition)
                val r = if (ring < 0) 0.0 else radius[ring]
                val a = if (ring < 0) 0.0 else angle(ring, place.toDouble())
                val age = (now - firing.at.toDouble()) / cps
                // Integer part: the colour. Fraction: a seed, so no two bursts match.
                put(vec(r * Math.cos(a), r * Math.sin(a), age, colour + (firing.index % 4093) / 4096.0))
            }
        }
    }

    // Each ring's token, between the place it is in and the next one.
    val marking = sparkRun.markingAt(at)
    val step = (now * 8) % 1.0
    rings.forEachIndexed { i, places ->
        tokens[i] = places.indexOfFirst { marking.count(it) > 0 } + step
    }
    // charge holds 0 to 4 tokens; the dust brightens as it fills.
    charge = sparkRun.countAt("charge", at) / 4.0
    true
}
