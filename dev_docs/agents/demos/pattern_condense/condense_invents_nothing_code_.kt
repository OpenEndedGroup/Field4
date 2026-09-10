import afield.four.synth.*
import kotlin.random.Random

// 2 - Invent nothing.
//
// The failure mode of a description-length search is naming a figure that is one step away
// from the rhythm in front of it. This box runs that check live, on masks with no structure
// put into them.
//
// POKE THIS: change `seed`, or `density` (one step in N is on), or `trials`. The count of
// wrong namings has to stay 0 whatever you set, because every mask that came back as a
// whole-span figure is recomputed here from bjorklund / kolakoskiMask and compared.
val seed = 20260910
val density = 3
val trials = 300

val random = Random(seed)
val euclidean = Regex("""^x\((\d+),(\d+)(?:,(\d+))?\)$""")
val kolakoskian = Regex("""^x\(([kg])(\d+)(?:,(\d+))?\)$""")

var named = 0
var wrong = 0
val ratios = mutableListOf<Double>()

repeat(trials) {
    val mask = List(16) { random.nextInt(density) == 0 }
    val condensed = patCondense(mask)
    ratios += condensed.compression
    euclidean.matchEntire(condensed.notation)?.let { m ->
        named++
        val pulses = m.groupValues[1].toInt()
        val steps = m.groupValues[2].toInt()
        val offset = m.groupValues[3].ifEmpty { "0" }.toInt()
        val figure = List(steps) { i -> bjorklund(pulses, steps)[((i + offset) % steps + steps) % steps] }
        if (figure != mask) wrong++
    }
    kolakoskian.matchEntire(condensed.notation)?.let { m ->
        named++
        val reading = if (m.groupValues[1] == "k") KolakoskiReading.RUNS else KolakoskiReading.GAPS
        val steps = m.groupValues[2].toInt()
        val offset = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (kolakoskiMask(steps, reading, rotation = -offset) != mask) wrong++
    }
}

val median = ratios.sorted()[ratios.size / 2]
val structured = patCondense(kolakoskiMask(16)).compression

markdown(
    "main",
    """
    ### $trials random 16-step masks, seed $seed, one step in $density on

    | | |
    |---|---|
    | read as a single whole-span figure | **$named** |
    | of those, wrong | **$wrong** |
    | median compression | ${"%.3f".format(median)} |
    | the same number for `kolakoskiMask(16)` | ${"%.3f".format(structured)} |

    Noise compresses about ${"%.1f".format(median / structured)} times worse than the figure
    it is up against. That separation is the claim. If random masks compressed as well as
    structured ones, the search would be finding figures in nothing.

    None of this rests on the search's own opinion of itself: every string it returned was
    re-parsed and re-played before it was accepted, and the namings above are recomputed from
    the generators rather than read off the label.
    """.trimIndent()
)
