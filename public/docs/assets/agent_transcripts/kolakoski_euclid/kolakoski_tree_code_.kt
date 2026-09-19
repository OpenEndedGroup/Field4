import afield.four.synth.*

// 2 - Kolakoski branches, Euclid places, and the levels are pulse levels.
//
// Every pulse asks the word how many children it has. bjorklund puts those children on the
// parent's own `slots` grid as evenly as it can, and each child runs until the next one - so
// a level always tiles the bar, and a count of 1 is a pulse that simply holds. The level
// sizes are the word's own partial sums: 1, 1, 2, 3, 4, 7, 11, ...
//
// POKE THIS: `slots` is where the Euclid lives - on 3 slots two children split the parent
// 2:1, not down the middle. `a`/`b` is the alphabet: {2,3} on 4 slots never leaves a lone
// hit. `depth` is how far down the tree the music goes.
val depth = 5
val slots = 3
val a = 1
val b = 2

fun tile(depth: Int, slots: Int, a: Int, b: Int): List<List<PatArc>> {
    val word = kolakoskiWord(4 shl depth, a, b)   // more terms than the tree can eat
    var level = listOf(PatArc(PatQ.ZERO, PatQ.ONE))
    var cursor = 0
    val levels = mutableListOf(level)
    repeat(depth) {
        val next = mutableListOf<PatArc>()
        for (parent in level) {
            val children = word[cursor++].coerceAtMost(slots)
            val step = parent.dur / PatQ.of(slots)
            val on = bjorklund(children, slots).withIndex().filter { it.value }.map { it.index }
            on.forEachIndexed { k, slot ->
                val start = parent.start + step * PatQ.of(slot)
                val end = if (k + 1 < on.size) parent.start + step * PatQ.of(on[k + 1]) else parent.end
                next += PatArc(start, end)
            }
        }
        level = next
        levels += level
    }
    return levels
}

fun asPat(arcs: List<PatArc>, name: String = "bd") = fromCycle { sub ->
    arcs.mapNotNull { arc -> arc.intersect(sub)?.let { PatEvent(it, name, whole = arc) } }
}

val t = tile(depth, slots, a, b)

// The word starts on a 1, so R1 is R0 held - the first branch that splits anything is R2.
showPatRollOf(
    *t.mapIndexed { n, level -> "R$n (${level.size})" to asPat(level) }.toTypedArray(),
    title = "each pulse subdivides into ${a} or ${b} on a ${slots}-slot grid", cycles = 1,
    channel = "tree"
)

// The rings as hierarchical pulse levels: one voice per level, so every hit of a fine level
// sits inside exactly one hit of the level above it.
// Coarse loud, fine quiet. Every coarse onset is also a fine onset - that is what a tree means -
// so at equal gain all four voices land together on the strong hits and the hierarchy turns into
// a pile. The gain ramp makes it foreground and background instead. Short samples for the same
// reason: sd:2 and click decay before the next level arrives.
val voices = listOf("bd" to 1.0, "sd:2" to 0.7, "hh:2" to 0.45, "click" to 0.35)
val played = t.takeLast(voices.size)
val music = stack(
    *played.mapIndexed { i, level ->
        asPat(level, voices[i].first) with gain(voices[i].second)
    }.toTypedArray()
)

showPatRollOf(
    *played.mapIndexed { i, level ->
        "${voices[i].first} (${level.size})" to asPat(level, voices[i].first)
    }.toTypedArray(),
    title = "the last four levels, one voice each", cycles = 2, channel = "levels"
)
val dirt = showDirtAudio(music, cycles = 8, cps = 1.0, channel = "audio")   // real samples, not patVoice
val audio = dirt.audio
val wav = "kolakoski-tree-${slots}-${a}${b}-d$depth.wav".file
afield.four.audio.WavFloatArrayWriter.writeStereo16(wav, audio.left, audio.right, PatSoundDocs.SR, 0.9f)
println("wrote $wav — samples: ${dirt.usedSamples}, matched ${dirt.matched}, synth fallback ${dirt.unmatched}")

markdown("main", """
### Level sizes: ${t.joinToString(", ") { it.size.toString() }}

Those are the partial sums of the Kolakoski word - each level eats as many terms as the level
above it has pulses, and the sum of those terms is the next level's size.
""")

displayOpen("tree")
displayOpen("audio")
displayOpen("levels")
displayOpen("main")
