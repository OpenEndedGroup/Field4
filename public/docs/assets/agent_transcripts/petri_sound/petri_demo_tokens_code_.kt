import afield.petri.*
import afield.four.synth.*

// A token's colour can be anything, including a whole PatPattern. So a place is a
// *section*, one circulating token is "which section is sounding", and the net enforces
// that exactly one of them is — no scheduler required.
val song = petriSong(
    "verse"  to (pat("bd ~ sd ~") to 4),
    "chorus" to (pat("bd*4 hh*8") to 4),
)

showPetriNet(song, w = 660.0, h = 340.0)

// `unfold` plays whatever pattern each firing consumed, across that firing's whole arc:
// the slow net schedules the fast one, and both are the same mechanism.
val notes = song.pattern().unfold<String>()

fun bar(c: Long) = notes.events(PatArc(PatQ.of(c), PatQ.of(c + 1)))
    .filter { it.hasOnset }.sortedBy { it.arc.start }.joinToString(" ") { it.value }
for (c in 0L..7L) println("cycle %d   %s".format(c, bar(c)))

// Eight cycles: four bars of verse, four of chorus, and audibly round again.
println(showDirtAudio(notes, cycles = 8, cps = 2.0, channel = "sound").describe())
displayOpen("sound")
