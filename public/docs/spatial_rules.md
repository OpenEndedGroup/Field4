# Spatial rules

A spatial rule starts a Kotlin process when it finds a pattern in moving points or edges. The
process can draw, publish points for another rule to find, and suspend with `nextFrame()`. Its
local variables carry over between frames. When the pattern breaks, the process can finish the
drawing on its own time.

This page starts with a tutorial that builds one piece a rule at a time. After it come six
finished pieces, three of which write out by hand what the tutorial's helpers do, and a summary
of the API.

## Build a web, one rule at a time

This tutorial grows one piece from a handful of moving points. Each step adds a rule, or changes
one, and shows the code as an edit to the step before. By the end, seeds drift across the
drawing, nearby seeds bond, each bond grows beads, beads on different bonds spin a web, broken
bonds drop their beads, and triangles of bonds fill in. No rule draws the whole picture: each one
reads what another has published.

The finished web is the **spatial_rules_web** starting point, under **Starting Points** on
Field's welcome screen or in the [agent transcript gallery](agent_transcripts.md#spatial_rules_web).
To build it yourself, put each step's code in one box and launch it with ++alt+arrow-up++. Stop it with
++alt+arrow-down++ before launching the next. Every step starts the seeds in the same places, so
each video below shows the same drift with one more layer on it.

### 1. Seeds that wander

Everything starts from a `SpatialRuleWorld`. A *source* is a process that publishes points, here
fourteen of kind `"seed"`, and moves them. Its body is ordinary Kotlin that runs until it reaches
`nextFrame()`, waits there for the next frame, and carries on with its local variables intact.
`age` is how many seconds the process has been running.

`wander` sends each seed across the drawing at its own pace and turns it back at the edges.
`tremble` adds a small, fast shake on top, the kind of noise tracked or simulated points usually
have; step 3 shows why that matters. `draw` submits an FLine for the current frame only, so the
source draws each seed again every frame. `play(world, w = w, h = h)` steps the world on Field's
animation clock and draws what it returns into an output panel, with world coordinates running
from (0, 0) to (`w`, `h`). Stopping the box closes the world. [Follow two points, then
fade](#follow-two-points-then-fade), further down, writes the same loop out by hand: a `loop { }`
that calls `world.step` with the clock's time and passes the lines it returns to `lines`.

```diffstatic_runner {data-lang=kotlin video="assets/spatial_rules/tutorial_seeds.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="6" fps="20" w="640" h="420" timeout="300" embed=true name="spatial-tutorial-seeds" alt="Fourteen pale dots drift across a dark rectangle, each at its own speed, turning back at the edges."}
val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

play(world, w = w, h = h)
```

### 2. Draw a line between nearby seeds

A rule group watches what the sources publish. `points("seed").pairsWithin(110.0)` matches every
pair of seeds closer than 110 units, and `onEnter` starts a process for each pair at the frame it
first matches. Inside, `match.live` stays true while the pair is still a match. `whileLive { }`
runs its block once a frame until it is not, so this process draws a line between the pair's
current positions for as long as they stay close.

```diff_runner {data-lang=kotlin video="assets/spatial_rules/tutorial_links.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="6" fps="20" w="640" h="420" timeout="300" embed=true name="spatial-tutorial-links" alt="Drifting dots with straight lines joining every pair that comes close; lines appear and vanish as the dots move."}
:::after
val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0)) { (a, b) ->
        whileLive { draw(spatialStroke([a.pos, b.pos], ink)) }
    }
}

play(world, w = w, h = h)
```

### 3. Join close, let go far

With a single distance, a pair that drifts slowly past 110 units, trembling, crosses back and
forth several times and starts a new process each time. A second distance stops that: with
`leave = 130.0`, a pair joins within 110 units but stays joined until it is more than 130 apart.
A counter makes the difference visible. It is an ordinary `var`, because the world runs its
processes one at a time on Field's animation loop. `play`'s `after` callback runs once each frame
has been drawn, so it can show the count beneath the drawing.

```diff_runner {data-lang=kotlin screenshot="assets/spatial_rules/tutorial_leave.png" capture="box" execution="launch" wait="12" open-outputs="true" w="640" h="420" timeout="240" embed=true name="spatial-tutorial-leave" alt="The drifting seeds joined by lines, with a Counts panel beneath reporting how many bonds have been made so far."}
:::after
val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        whileLive { draw(spatialStroke([a.pos, b.pos], ink)) }
    }
}

play(world, w = w, h = h, after = { markdown("Counts", "$bondsMade bonds made") })
```

Here is the same rule with `leave` taken out again. Run each for the same time and compare the
counts: the extra bonds are pairs that came and went on either side of 110 units. Fewer lines
stand at any moment, too, since each now ends at 110 rather than at 130.

```diff_runner {data-lang=kotlin screenshot="assets/spatial_rules/tutorial_no_leave.png" capture="box" execution="launch" wait="12" open-outputs="true" w="640" h="420" timeout="240" embed=true name="spatial-tutorial-no-leave" variant=true alt="The same drawing with fewer lines standing, and a higher count of bonds made."}
:::after
val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0)) { (a, b) ->
        bondsMade++
        whileLive { draw(spatialStroke([a.pos, b.pos], ink)) }
    }
}

play(world, w = w, h = h, after = { markdown("Counts", "$bondsMade bonds made") })
```

### 4. Let a line end in its own time

A process does not have to end with its match. The code after `whileLive` runs once the pair has
separated. `during(0.8) { t -> }` runs its block once a frame for 0.8 seconds, with `t` going from
0 toward 1, so the line fades out instead of vanishing. Its ends are still the live seeds, so a
fading line keeps stretching as they pull apart. Try `during(3.0)` to see old links pile up.
The [filament](#follow-two-points-then-fade) is this step on its own, with `whileLive` and
`during` written as the `while` loops they stand for.

```diff_runner {data-lang=kotlin video="assets/spatial_rules/tutorial_fade.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="6" fps="20" w="640" h="420" timeout="300" embed=true name="spatial-tutorial-fade" alt="Lines between drifting dots; when two dots part, their line stretches and fades out instead of disappearing."}
:::after
val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        whileLive { draw(spatialStroke([a.pos, b.pos], ink)) }
        during(0.8) { t -> draw(spatialStroke([a.pos, b.pos], ink, opacity = 1 - t)) }
    }
}

play(world, w = w, h = h, after = { markdown("Counts", "$bondsMade bonds made") })
```

### 5. Publish the bond, and bead it

So far the lines are only drawn, and no other rule can see them. `edge("bond", a, b)` publishes
each one as an edge, and a second rule group, `"beads"`, matches `edges("bond")`: one process per
bond. That process publishes points of its own. It adds a bead every 0.2 seconds until the bond
carries one for each 22 units of its length, and each frame it slides the beads to evenly spaced
places along the bond with `bond.at(fraction)`. When the bond gets shorter, it retracts beads from
the end. Each bead records the bond that made it in its data, `mapOf("bond" to bond.id)`, which a
later step reads.

Rules see what was published by the end of the previous frame, so each layer runs a frame behind
the one it reads. An edge, like a point, lasts until the process that published it returns.

```diff_runner {data-lang=kotlin video="assets/spatial_rules/tutorial_beads.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="8" fps="20" w="640" h="420" timeout="300" embed=true name="spatial-tutorial-beads" alt="Each line between drifting dots fills with small amber beads, added one at a time and kept evenly spaced as the line stretches and shrinks."}
:::after
val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val beadInk = vec(1.0, 0.72, 0.35, 1.0)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        val bond = edge("bond", a, b)
        whileLive { draw(spatialStroke(bond, ink)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, opacity = 1 - t)) }
    }
}

world.rules("beads") {
    onEnter("bead a bond", edges("bond")) { bond ->
        val beads = mutableListOf<SpatialPoint>()
        whileLive {
            val beadSpacing = 22 // Drawing units per bead along the bond.
            val wanted = (bond.length / beadSpacing).toInt()
            if (beads.size < wanted && age > 0.2 * beads.size) {
                beads += point("bead", bond.at(0.5), mapOf("bond" to bond.id))
            }
            while (beads.size > wanted) retract(beads.removeLast())
            for ((i, bead) in beads.withIndex()) {
                move(bead, bond.at((i + 1.0) / (beads.size + 1)))
                draw(spatialDot(bead.pos, 1.8, beadInk))
            }
        }
    }
}

play(world, w = w, h = h, after = { markdown("Counts", "$bondsMade bonds made") })
```

### 6. When a bond breaks, let its beads fall

When a bond breaks, its bead process sees `match.live` turn false. If it returned there, its beads
would be retracted at once. Instead, `during(1.5)` moves each bead down from where it was, faster
and faster, and fades it out. The beads are still published while they fall, so other rules can
still find them.

That needs one more change. By default the bond lasts as long as the process that published it,
and that process spends 0.8 seconds fading its line. The beads would ride the fading line and fall
only once it had gone. `lifetime = MATCH` retracts the bond the moment its seeds part, while the
line goes on fading. `MATCH` comes from `SpatialLifetime`, imported at the top. The
[loom](#a-loom-with-memory) does the same with the gold knot each stitch publishes: the knot goes
when the stitch's tips part, and the stitch's curve curls and fades after it.

```diff_runner {data-lang=kotlin video="assets/spatial_rules/tutorial_fall.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="8" fps="20" w="640" h="420" timeout="300" embed=true name="spatial-tutorial-fall" alt="Beaded lines between drifting dots; when a line breaks, its amber beads drop away downward and fade."}
:::after
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val beadInk = vec(1.0, 0.72, 0.35, 1.0)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, opacity = 1 - t)) }
    }
}

world.rules("beads") {
    onEnter("bead a bond", edges("bond")) { bond ->
        val beads = mutableListOf<SpatialPoint>()
        whileLive {
            val beadSpacing = 22 // Drawing units per bead along the bond.
            val wanted = (bond.length / beadSpacing).toInt()
            if (beads.size < wanted && age > 0.2 * beads.size) {
                beads += point("bead", bond.at(0.5), mapOf("bond" to bond.id))
            }
            while (beads.size > wanted) retract(beads.removeLast())
            for ((i, bead) in beads.withIndex()) {
                move(bead, bond.at((i + 1.0) / (beads.size + 1)))
                draw(spatialDot(bead.pos, 1.8, beadInk))
            }
        }
        val from = beads.map { it.pos }
        during(1.5) { t ->
            for ((i, bead) in beads.withIndex()) {
                move(bead, from[i] + vec(0, 120 * t * t, 0))
                draw(spatialDot(bead.pos, 1.8, beadInk, opacity = 1 - t))
            }
        }
    }
}

play(world, w = w, h = h, after = { markdown("Counts", "$bondsMade bonds made") })
```

### 7. A web between the beads

Beads are points like any other, so a rule can look for pairs of them. Beads from different bonds
that come within 24 units get a web strand, held until they are 32 apart. `.where { (a, b) -> ... }`
filters the pairs with ordinary Kotlin, here reading the bond each bead was published with. Near a
seed, where several bonds meet, their beads are close together and the web fills the angle between
them. Two bonds that break at about the same time drop their beads together, and the falling beads
trail a zig-zag curtain of strands. No rule mentions curtains: they come from the web rule reading
the falling beads. In the [loom](#a-loom-with-memory), the gold weave plays the web's part: it
pairs knots that stitches publish, and its `where` keeps the pairs within one station.

The counter now also reports how many web strands stand at that moment.

```diff_runner {data-lang=kotlin video="assets/spatial_rules/tutorial_web.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="8" fps="20" w="640" h="420" timeout="300" embed=true name="spatial-tutorial-web" alt="Beaded lines between drifting seeds, with fine blue strands joining beads of neighbouring lines; when lines break, falling beads trail zig-zag strands."}
:::after
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val beadInk = vec(1.0, 0.72, 0.35, 1.0)
val webInk = vec(0.45, 0.8, 1.0, 0.8)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, opacity = 1 - t)) }
    }
}

world.rules("beads") {
    onEnter("bead a bond", edges("bond")) { bond ->
        val beads = mutableListOf<SpatialPoint>()
        whileLive {
            val beadSpacing = 22 // Drawing units per bead along the bond.
            val wanted = (bond.length / beadSpacing).toInt()
            if (beads.size < wanted && age > 0.2 * beads.size) {
                beads += point("bead", bond.at(0.5), mapOf("bond" to bond.id))
            }
            while (beads.size > wanted) retract(beads.removeLast())
            for ((i, bead) in beads.withIndex()) {
                move(bead, bond.at((i + 1.0) / (beads.size + 1)))
                draw(spatialDot(bead.pos, 1.8, beadInk))
            }
        }
        val from = beads.map { it.pos }
        during(1.5) { t ->
            for ((i, bead) in beads.withIndex()) {
                move(bead, from[i] + vec(0, 120 * t * t, 0))
                draw(spatialDot(bead.pos, 1.8, beadInk, opacity = 1 - t))
            }
        }
    }
}

world.rules("web") {
    val across = points("bead").pairsWithin(24.0, leave = 32.0).where { (a, b) -> a["bond"] != b["bond"] }
    onEnter("web across bonds", across) { (a, b) ->
        edge("web", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke([a.pos, b.pos], webInk, width = 0.8)) }
        during(0.4) { t -> draw(spatialStroke([a.pos, b.pos], webInk, 0.8, 1 - t)) }
    }
}

play(world, w = w, h = h, after = {
    markdown("Counts", "$bondsMade bonds made, ${world.edges("web").size} web strands now")
})
```

Without the `where`, a bead's nearest neighbours are the beads beside it on its own bond, so
strands also run along every bond, doubling the line they sit on, and the count of standing
strands climbs.

```diff_runner {data-lang=kotlin screenshot="assets/spatial_rules/tutorial_web_unfiltered.png" capture="box" execution="launch" wait="12" open-outputs="true" w="640" h="420" timeout="240" embed=true name="spatial-tutorial-web-unfiltered" variant=true alt="The web with blue strands also running along each bonded line, and a much higher count of web strands in the panel beneath."}
:::after
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val beadInk = vec(1.0, 0.72, 0.35, 1.0)
val webInk = vec(0.45, 0.8, 1.0, 0.8)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, opacity = 1 - t)) }
    }
}

world.rules("beads") {
    onEnter("bead a bond", edges("bond")) { bond ->
        val beads = mutableListOf<SpatialPoint>()
        whileLive {
            val beadSpacing = 22 // Drawing units per bead along the bond.
            val wanted = (bond.length / beadSpacing).toInt()
            if (beads.size < wanted && age > 0.2 * beads.size) {
                beads += point("bead", bond.at(0.5), mapOf("bond" to bond.id))
            }
            while (beads.size > wanted) retract(beads.removeLast())
            for ((i, bead) in beads.withIndex()) {
                move(bead, bond.at((i + 1.0) / (beads.size + 1)))
                draw(spatialDot(bead.pos, 1.8, beadInk))
            }
        }
        val from = beads.map { it.pos }
        during(1.5) { t ->
            for ((i, bead) in beads.withIndex()) {
                move(bead, from[i] + vec(0, 120 * t * t, 0))
                draw(spatialDot(bead.pos, 1.8, beadInk, opacity = 1 - t))
            }
        }
    }
}

world.rules("web") {
    val across = points("bead").pairsWithin(24.0, leave = 32.0)
    onEnter("web across bonds", across) { (a, b) ->
        edge("web", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke([a.pos, b.pos], webInk, width = 0.8)) }
        during(0.4) { t -> draw(spatialStroke([a.pos, b.pos], webInk, 0.8, 1 - t)) }
    }
}

play(world, w = w, h = h, after = {
    markdown("Counts", "$bondsMade bonds made, ${world.edges("web").size} web strands now")
})
```

### 8. Membranes over triangles

`edges("bond").triangles()` matches every three seeds that are bonded pairwise, once per
triangle, and gives the process the three points and the three bonds. This process fills the
triangle while it holds and fades it out afterwards. The fill rises over its first half second,
using the process's `age`.

```diff_runner {data-lang=kotlin video="assets/spatial_rules/tutorial_membranes.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="8" fps="20" w="640" h="420" timeout="300" embed=true name="spatial-tutorial-membranes" alt="Triangles of bonded seeds fill with a translucent blue membrane that fades in, holds while the triangle lasts, and fades out when one of its bonds breaks."}
:::after
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val beadInk = vec(1.0, 0.72, 0.35, 1.0)
val webInk = vec(0.45, 0.8, 1.0, 0.8)
val skin = vec(0.35, 0.55, 0.9, 0.22)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, opacity = 1 - t)) }
    }
}

world.rules("beads") {
    onEnter("bead a bond", edges("bond")) { bond ->
        val beads = mutableListOf<SpatialPoint>()
        whileLive {
            val beadSpacing = 22 // Drawing units per bead along the bond.
            val wanted = (bond.length / beadSpacing).toInt()
            if (beads.size < wanted && age > 0.2 * beads.size) {
                beads += point("bead", bond.at(0.5), mapOf("bond" to bond.id))
            }
            while (beads.size > wanted) retract(beads.removeLast())
            for ((i, bead) in beads.withIndex()) {
                move(bead, bond.at((i + 1.0) / (beads.size + 1)))
                draw(spatialDot(bead.pos, 1.8, beadInk))
            }
        }
        val from = beads.map { it.pos }
        during(1.5) { t ->
            for ((i, bead) in beads.withIndex()) {
                move(bead, from[i] + vec(0, 120 * t * t, 0))
                draw(spatialDot(bead.pos, 1.8, beadInk, opacity = 1 - t))
            }
        }
    }
}

world.rules("web") {
    val across = points("bead").pairsWithin(24.0, leave = 32.0).where { (a, b) -> a["bond"] != b["bond"] }
    onEnter("web across bonds", across) { (a, b) ->
        edge("web", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke([a.pos, b.pos], webInk, width = 0.8)) }
        during(0.4) { t -> draw(spatialStroke([a.pos, b.pos], webInk, 0.8, 1 - t)) }
    }
}

world.rules("membranes") {
    onEnter("fill a triangle", edges("bond").triangles()) { triangle ->
        whileLive { draw(spatialFill(triangle, skin, opacity = min(1.0, age / 0.5))) }
        during(0.5) { t -> draw(spatialFill(triangle, skin, opacity = 1 - t)) }
    }
}

play(world, w = w, h = h, after = {
    markdown("Counts", "$bondsMade bonds made, ${world.edges("web").size} web strands now")
})
```

### 9. Change one rule while the others run

Installing a rule group under a name that is already in use replaces only that group. Its
processes stop, what they published is retracted, and the new rules start matching on the next
frame. The seeds, bonds, beads and membranes carry on. While the box is running, edit the web
block to give it a wider reach and a red ink, select the whole `world.rules("web") { ... }`
block, and press ++alt+enter++ to run just the selection:

```diffstatic {data-lang=kotlin variant=true}
world.rules("web") {
    val across = points("bead").pairsWithin(40.0, leave = 50.0).where { (a, b) -> a["bond"] != b["bond"] }
    onEnter("web across bonds", across) { (a, b) ->
        edge("web", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke([a.pos, b.pos], vec(1.0, 0.35, 0.3, 0.9), width = 0.8)) }
        during(0.4) { t -> draw(spatialStroke([a.pos, b.pos], vec(1.0, 0.35, 0.3, 0.9), 0.8, 1 - t)) }
    }
}
```

The web turns red and denser from the next frame, and nothing else in the drawing restarts. Run
the original block again to put it back. Sources work the same way: `world.source(name) { }`
replaces the source of that name, and `world.stop(name)` ends a group or a source.

### 10. Ask why a strand is there

When rules feed rules, it helps to ask where a particular line came from. Every point and edge
records the process that published it and what that process was matched to.
`world.nearest(at, within = 12.0)` finds the live point or edge nearest to a position, and
[`world.lineage(fact)`](reference/data.md#spatial-rules-kt--spatialruleworld--lineage) walks back through what made it: a web strand, the two beads it joins, the
bonds that made those beads, and the seeds at their ends.

`play` passes each press on the drawing to `onPress` in world coordinates. The callback runs
between frames, so it can read the world and set `traced`, which a small `"highlight"` source
draws a glow under. Press near a strand, and the Why panel lists the strand, its beads, their
bonds and their seeds, each with the rule that made it. A strand between falling beads traces
back only to the beads, because their bonds have already been retracted. The
[loom](#a-loom-with-memory) answers the same question one step deep: clicking a gold knot shows
`world.explain(knot)`, the rule that made it and the two tips it came from.

```diff {data-lang=kotlin}
:::after
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val beadInk = vec(1.0, 0.72, 0.35, 1.0)
val webInk = vec(0.45, 0.8, 1.0, 0.8)
val skin = vec(0.35, 0.55, 0.9, 0.22)
val glow = vec(1.0, 0.9, 0.4, 0.6)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 14 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, opacity = 1 - t)) }
    }
}

world.rules("beads") {
    onEnter("bead a bond", edges("bond")) { bond ->
        val beads = mutableListOf<SpatialPoint>()
        whileLive {
            val beadSpacing = 22 // Drawing units per bead along the bond.
            val wanted = (bond.length / beadSpacing).toInt()
            if (beads.size < wanted && age > 0.2 * beads.size) {
                beads += point("bead", bond.at(0.5), mapOf("bond" to bond.id))
            }
            while (beads.size > wanted) retract(beads.removeLast())
            for ((i, bead) in beads.withIndex()) {
                move(bead, bond.at((i + 1.0) / (beads.size + 1)))
                draw(spatialDot(bead.pos, 1.8, beadInk))
            }
        }
        val from = beads.map { it.pos }
        during(1.5) { t ->
            for ((i, bead) in beads.withIndex()) {
                move(bead, from[i] + vec(0, 120 * t * t, 0))
                draw(spatialDot(bead.pos, 1.8, beadInk, opacity = 1 - t))
            }
        }
    }
}

world.rules("web") {
    val across = points("bead").pairsWithin(24.0, leave = 32.0).where { (a, b) -> a["bond"] != b["bond"] }
    onEnter("web across bonds", across) { (a, b) ->
        edge("web", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke([a.pos, b.pos], webInk, width = 0.8)) }
        during(0.4) { t -> draw(spatialStroke([a.pos, b.pos], webInk, 0.8, 1 - t)) }
    }
}

world.rules("membranes") {
    onEnter("fill a triangle", edges("bond").triangles()) { triangle ->
        whileLive { draw(spatialFill(triangle, skin, opacity = min(1.0, age / 0.5))) }
        during(0.5) { t -> draw(spatialFill(triangle, skin, opacity = 1 - t)) }
    }
}

var traced = emptyList<SpatialFact>()

world.source("highlight") {
    while (true) {
        for (fact in traced.filter { it.alive }) when (fact) {
            is SpatialPoint -> draw(spatialDot(fact.pos, 6.0, glow))
            is SpatialEdge -> draw(spatialStroke(fact, glow, width = 5.0))
            is SpatialLink -> draw(spatialStroke(fact.attached.map { it.pos }, glow, width = 5.0))
        }
        nextFrame()
    }
}

play(
    world,
    w = w,
    h = h,
    after = { markdown("Counts", "$bondsMade bonds made, ${world.edges("web").size} web strands now") },
    onPress = { at ->
        traced = world.nearest(at, within = 12.0)?.let(world::lineage).orEmpty()
        markdown("Why", traced.joinToString("\n") { "- **${it.kind} #${it.id}** by *${it.origin.process}*" })
    },
)
```

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="srw_trace" screenshot="assets/spatial_rules/tutorial_trace.png" transcript="dev_docs/agents/transcripts/docs_capture/spatial_rules_trace.yaml" padding="0" wait="0" timeout="300" embed=true hidden=true name="spatial-tutorial-trace" alt="The web after a press on one strand: the strand, the beads it joins, the bonds those beads sit on and the seeds at their ends glow yellow, and the Why panel beneath lists each of them with the rule that made it."}
/*
The transcript built step 10's box and launched it. Press near web strands that lie over a
membrane, as a reader would, until one traces all the way back to its seeds: a strand between
falling beads stops at the beads.
*/
fun press(attempt: Int) =
    """
    const colour = (e, a) => (e.getAttribute(a) || '').toLowerCase();
    const strands = [...document.querySelectorAll('svg path')].filter(p => /115.*204.*255|#73ccff/.test(colour(p, 'stroke')));
    const over = strands.filter(strand => {
      const r = strand.getBoundingClientRect();
      return document.elementsFromPoint(r.left + r.width / 2, r.top + r.height / 2)
        .some(e => e.tagName === 'path' && colour(e, 'fill') && colour(e, 'fill') !== 'none');
    });
    if (!over.length) return false;
    const strand = over[$attempt % over.length];
    const r = strand.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    let target = strand;
    while (target && !target.onpointerdown) target = target.parentElement;
    if (!target) return false;
    const at = {bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0, pointerId: 7, isPrimary: true, pointerType: 'mouse'};
    target.dispatchEvent(new PointerEvent('pointerdown', at));
    target.dispatchEvent(new PointerEvent('pointerup', at));
    return true;
    """

val tracedToSeeds =
    """
    const text = document.body.innerText;
    return text.includes('web #') && (text.match(/seed #/g) || []).length >= 2;
    """
var traced = false
for (attempt in 0 until 60) {
    if (traced) break
    ui.eval(press(attempt))
    ui.pause(250)
    traced = ui.eval(tracedToSeeds).toString() == "true"
}
check(traced) { "no press traced a strand back to two seeds" }
ui.pause(500)
```

??? aside "Agents: the step 10 figure"
    A `diff_runner` fence runs its code and photographs it, but cannot press on the drawing, so
    step 10's figure comes from the hidden `capture="ui"` fence above it. That fence replays
    `dev_docs/agents/transcripts/docs_capture/spatial_rules_trace.yaml`, which builds and
    launches a box holding step 10's code, then its drive script presses a web strand the way a
    reader would. The press is real input, through the same path a mouse takes, so the figure is
    not faked. The transcript's box code must equal the step 10 fence, and the
    `spatial_rules_web` starting point's must equal step 11's;
    `tools/test_spatial_rules_transcripts.py` fails when either drifts. Change a fence and copy
    it into the transcript in the same commit.

### 11. Make it bigger

The same rules, with thirty seeds on a 900 by 500 drawing. This is the
**spatial_rules_web** starting point. For a thousand points, see the
[torus](#a-thousand-moving-points) and [Scaling up](#scaling-up).

```diff_runner {data-lang=kotlin video="assets/spatial_rules/tutorial_finale.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="10" fps="20" w="940" h="600" timeout="300" embed=true name="spatial-tutorial-finale" alt="Thirty drifting seeds in a wide drawing: bonded lines full of amber beads, blue lace webs filling the angles where lines meet, translucent triangles, and curtains of falling beads."}
:::after
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 900.0
val h = 500.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val beadInk = vec(1.0, 0.72, 0.35, 1.0)
val webInk = vec(0.45, 0.8, 1.0, 0.8)
val skin = vec(0.35, 0.55, 0.9, 0.22)
val glow = vec(1.0, 0.9, 0.4, 0.6)

// Each seed crosses the drawing at its own pace and turns back at the edges.
fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun tremble(i: Int, t: Double) = vec(2.5 * sin(19 * t + i), 2.5 * cos(23 * t + 2 * i), 0)

world.source("drifting seeds") {
    val seeds = 30 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) {
            move(seed, wander(i, age) + tremble(i, age))
            draw(spatialDot(seed.pos, 3.0, ink))
        }
        nextFrame()
    }
}

var bondsMade = 0

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(110.0, leave = 130.0)) { (a, b) ->
        bondsMade++
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, opacity = 1 - t)) }
    }
}

world.rules("beads") {
    onEnter("bead a bond", edges("bond")) { bond ->
        val beads = mutableListOf<SpatialPoint>()
        whileLive {
            val beadSpacing = 22 // Drawing units per bead along the bond.
            val wanted = (bond.length / beadSpacing).toInt()
            if (beads.size < wanted && age > 0.2 * beads.size) {
                beads += point("bead", bond.at(0.5), mapOf("bond" to bond.id))
            }
            while (beads.size > wanted) retract(beads.removeLast())
            for ((i, bead) in beads.withIndex()) {
                move(bead, bond.at((i + 1.0) / (beads.size + 1)))
                draw(spatialDot(bead.pos, 1.8, beadInk))
            }
        }
        val from = beads.map { it.pos }
        during(1.5) { t ->
            for ((i, bead) in beads.withIndex()) {
                move(bead, from[i] + vec(0, 120 * t * t, 0))
                draw(spatialDot(bead.pos, 1.8, beadInk, opacity = 1 - t))
            }
        }
    }
}

world.rules("web") {
    val across = points("bead").pairsWithin(24.0, leave = 32.0).where { (a, b) -> a["bond"] != b["bond"] }
    onEnter("web across bonds", across) { (a, b) ->
        edge("web", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke([a.pos, b.pos], webInk, width = 0.8)) }
        during(0.4) { t -> draw(spatialStroke([a.pos, b.pos], webInk, 0.8, 1 - t)) }
    }
}

world.rules("membranes") {
    onEnter("fill a triangle", edges("bond").triangles()) { triangle ->
        whileLive { draw(spatialFill(triangle, skin, opacity = min(1.0, age / 0.5))) }
        during(0.5) { t -> draw(spatialFill(triangle, skin, opacity = 1 - t)) }
    }
}

var traced = emptyList<SpatialFact>()

world.source("highlight") {
    while (true) {
        for (fact in traced.filter { it.alive }) when (fact) {
            is SpatialPoint -> draw(spatialDot(fact.pos, 6.0, glow))
            is SpatialEdge -> draw(spatialStroke(fact, glow, width = 5.0))
            is SpatialLink -> draw(spatialStroke(fact.attached.map { it.pos }, glow, width = 5.0))
        }
        nextFrame()
    }
}

play(
    world,
    w = w,
    h = h,
    after = { markdown("Counts", "$bondsMade bonds made, ${world.edges("web").size} web strands now") },
    onPress = { at ->
        traced = world.nearest(at, within = 12.0)?.let(world::lineage).orEmpty()
        markdown("Why", traced.joinToString("\n") { "- **${it.kind} #${it.id}** by *${it.origin.process}*" })
    },
)
```

### What the web is made of

Each rule reads what the rules above it publish:

| Group | Matches | Publishes | Draws |
| --- | --- | --- | --- |
| `drifting seeds` (a source) | | `seed` points | the seeds |
| `bonds` | pairs of seeds within 110, held to 130 | a `bond` edge, until the pair parts | the bond, then its fade |
| `beads` | each `bond` | `bead` points, kept while they fall | the beads |
| `web` | pairs of beads from different bonds within 24, held to 32 | a `web` edge, until the pair parts | the strand, then its fade |
| `membranes` | each triangle of bonds | | the fill |
| `highlight` (a source) | | | a glow under each traced fact |

## Six finished pieces

The first three pieces use the same rules as the tutorial, but write their loops out by hand.
`while (match.live) { ...; nextFrame() }` is what `whileLive` does, a `while` loop against `now`
is `during`, and the box's own `loop { }` around `world.step` is `play`. Read them side by side
with the tutorial steps they echo. The fourth, a set of ropes, uses the helpers and adds a little
physics. The fifth keeps a minimum spanning tree, which it computes itself, over seeds that come and go.
The sixth pins shapes between moving points, and runs its rules on several threads at once.

Open **spatial_rules_tour** under **Starting Points** on Field’s welcome screen or from the
[agent transcript gallery](agent_transcripts.md#spatial_rules_tour). Run **Start here · run the tour**
to open the canvas guide. Launch each numbered box with ++alt+arrow-up++, and stop it with ++alt+arrow-down++.

### Follow two points, then fade

**One filament** alternates between pulling its input tips apart and removing them while they
are still close. The stitch is born within 100 units and stays alive until the tips are more
than 120 units apart or either disappears. It remembers the last positions it drew and fades
that geometry over 0.7 seconds. Click the live or fading line to see what made it, how old it
is, and which tips and conditions are still live. Try a two-second fade to make the fading
line easier to pick.
It is tutorial steps [2](#2-draw-a-line-between-nearby-seeds) to
[4](#4-let-a-line-end-in-its-own-time) for a single pair, with the tips made and retracted by a
source rather than wandering forever.

```diffstatic_runner {data-lang=kotlin video="assets/spatial_rules/filament.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="7" fps="20" w="720" h="330" timeout="180" embed=true name="spatial-filament-film" alt="Two points pull apart and leave a fading line, then disappear while still close and leave another fading line."}
import afield.data.graph.SpatialLifetime.MATCH
import afield.data.graph.inspectOnClick

val world = SpatialRuleWorld()
val ink = Vec4(0.8f, 0.92f, 1f, 1f)

world.source("moving tips") {
    var pullApart = true
    while (true) {
        val left = point("tip", Vec3(110f, 100f, 0f))
        val right = point("tip", Vec3(170f, 100f, 0f))
        val born = now
        val travel = if (pullApart) 170.0 else 45.0
        while (now - born < 2.2) {
            val position = vec(170 + travel * ((now - born) / 2.2), 100, 0)
            move(right, position)
            draw(spatialDot(left.pos, 4.0, ink))
            draw(spatialDot(right.pos, 4.0, ink))
            nextFrame()
        }
        retract(left)
        retract(right)
        pullApart = !pullApart
        val released = now
        while (now - released < 1.0) nextFrame()
    }
}

world.rules("filaments") {
    val close = points("tip").pairsWithin(100.0, leave = 120.0)
    onEnter("join tips", close, condition = "Both tips are present within the 120-unit release distance") { (a, b) ->
        val filament = edge("filament", a, b, lifetime = MATCH)
        fun stroke(ends: List<Vec3>, opacity: Double = 1.0): FLine {
            return world.inspectOnClick(filament, spatialStroke(ends, ink, width = 2.0, opacity = opacity)) { why ->
                val phase = if (why.matchLive == true) "Matched" else "Fading"
                val inputs =
                    why.inputs.joinToString { input ->
                        val status = if (input.live) "live" else "retracted"
                        "${input.kind} #${input.id} ($status)"
                    }
                markdown(
                    "Why this filament?",
                    "**$phase** · ${why.ageSeconds.f2} s old\n\n**${why.origin.process}** · $inputs\n\n${why.origin.condition}",
                )
            }
        }
        var ends = [a.pos, b.pos]
        while (match.live) {
            ends = [a.pos, b.pos]
            draw(stroke(ends))
            nextFrame()
        }

        val released = now
        while (now - released < 0.7) {
            draw(stroke(ends, opacity = 1.0 - (now - released) / 0.7))
            nextFrame()
        }
    }
}

displayOpen("filament")
val previewInterval = 1.0 / 60.0
var lastPreview = Double.NEGATIVE_INFINITY
loop {
    val time = AnimationClock.nowSeconds()
    if (time - lastPreview + 1e-6 >= previewInterval) {
        lastPreview = time
        lines(
            world.step(time),
            channel = "filament",
            w = 460.0,
            h = 200.0,
            fitToSize = true,
            framing = LinesFraming.Fixed(0.0, 0.0, 460.0, 200.0),
        )
    }
}.end { world.close() }
```

### A loom with memory

Three groups of tips move independently in 3D. Each group’s two rows enter and leave in
staggered waves. The picture is a projection of the points, and the proximity tests use all
three coordinates.

- Cyan stitches are cubic curves. Increasing the distance between their tips widens the
  stroke. When a stitch loses a tip, its last curve curls inward and thins as it fades.
- Gold weave edges join nearby knots that stitches publish. Violet echoes are separate edges
  over the endpoints of taut stitches. The three kinds can enter and leave on different frames.
- Pale ellipses fit the projected positions of each group’s current knots. Their axes come
  from the covariance of the cloud. A minimum spanning tree joins the visible ellipse centres.
  Its ribbons taper toward each endpoint.

This is the tutorial's [beads](#5-publish-the-bond-and-bead-it) and
[web](#7-a-web-between-the-beads) in another form: a rule over tips publishes knots, as the bond
rule publishes beads, and another rule weaves the knots. Click a gold knot in Field to inspect its
producing rule, source tips, age, and condition, as [step 10](#10-ask-why-a-strand-is-there)
traces a strand.
Try changing `departureFade`, or lower the threshold of the taut-stitch rule to see more
echoes. The tree ribbons use [`byVariableRibboning`](reference/graphics.md#flinethickening-kt--byvariableribboning)
to give each bowed centreline a width that tapers to zero at either end.

```diffstatic_runner {data-lang=kotlin video="assets/spatial_rules/loom.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="14" fps="24" w="1050" h="475" timeout="360" embed=true name="spatial-loom-film" alt="Three animated formations of curved cyan stitches, gold knot links and violet echoes. Pale fitted ellipses and tapered tree ribbons stretch between the formations as live tips arrive and disappear and old curves fade."}
import afield.data.graph.SpatialLifetime.MATCH
import afield.data.graph.inspectOnClick
import java.util.concurrent.atomic.AtomicLong

val world = SpatialRuleWorld()
val stitchInk = vec(0.27, 0.77, 0.93, 0.74)
val weaveInk = vec(1.0, 0.70, 0.29, 0.82)
val echoInk = vec(0.80, 0.53, 0.97, 0.73)
val knotInk = vec(1.0, 0.90, 0.61, 1.0)
val treeInk = vec(0.73, 0.88, 0.99, 0.8)
val inspected = AtomicLong(-1L)
val stations = 3
val tipsPerRow = 7
val departureFade = 1.3

fun midpoint(a: Vec3, b: Vec3) = Vec3(a).add(b).mul(0.5f)
fun rise(age: Double, duration: Double) = (age / duration).coerceIn(0.0, 1.0)
fun project(p: Vec3) = vec(p.x + 0.22 * p.z, p.y - 0.35 * p.z, 0)

fun bow(a: Vec3, b: Vec3, offset: Double, ink: Vec4, width: Double, opacity: Double): FLine {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = hypot(dx.toDouble(), dy.toDouble()).coerceAtLeast(1.0)
    val n = vec(-dy * offset / length, dx * offset / length, 0)
    val c1 = vec(a.x + dx / 3, a.y + dy / 3, 0).add(n)
    val c2 = vec(b.x - dx / 3, b.y - dy / 3, 0).add(n)
    return FLine().moveTo(a).cubicTo(c1, c2, b).apply {
        color = vec(ink.x, ink.y, ink.z, ink.w * opacity)
        thickness = width
    }
}

// Bow the centreline, then give it a width that tapers to zero at both ends.
fun ribbon(a: Vec3, b: Vec3, width: Double, opacity: Double): FLine =
    bow(a, b, 17.0, treeInk, 1.0, opacity)
        .byVariableRibboning(parameterFromLambda { t, _ -> (2.0 * width * sin(PI * t)).toFloat() }, samples = 24)
        .apply {
            color = vec(treeInk.x, treeInk.y, treeInk.z, treeInk.w * opacity)
            filled = true
            stroked = false
        }

world.source("shuttles") {
    val start = now
    val rows = Array(stations) { Array(2) { arrayOfNulls<SpatialPoint>(tipsPerRow) } }
    while (true) {
        val t = now - start
        for (station in 0 until stations) for (row in 0..1) {
            val side = if (row == 0) -1 else 1
            for (i in 0 until tipsPerRow) {
                val phase = t * 1.1 + i * 0.56 + station * 0.8
                val cycle = (t + station * 2.5 + i * 0.19 + row * 0.8) % 9.5
                val present = cycle < if (row == 0) 8.3 else 6.3
                val p = vec(
                    150 + station * 260 + (i - 3) * 24 + 20 * sin(phase + row),
                    220 + 38 * sin(t * 0.6 + station * 1.4) + side * (36 + 23 * cos(phase * 0.7)) + 28 * sin(phase),
                    24 * cos(phase + row * 1.7),
                )
                val tip = rows[station][row][i]
                if (present) {
                    if (tip == null) {
                        rows[station][row][i] = point("tip", p, mapOf("station" to station, "row" to row))
                    } else {
                        move(tip, p)
                        draw(spatialDot(project(tip.pos), 2.6, if (row == 0) stitchInk else echoInk))
                    }
                } else if (tip != null) {
                    retract(tip)
                    rows[station][row][i] = null
                }
            }
        }
        nextFrame()
    }
}

world.rules("loom") {
    val tips = points("tip")
    val knots = points("knot")
    val opposing =
        tips.pairsWithin(80.0, leave = 101.0).where { (a, b) ->
            a["station"] == b["station"] && a["row"] != b["row"]
        }

    onEnter(
        "bow a stitch",
        opposing,
        condition = "Same station, opposite rows, both live and within 101 units (80 to join)",
    ) { (a, b) ->
        val strand = edge("stitch", a, b, lifetime = MATCH)
        val knot = point("knot", midpoint(a.pos, b.pos), mapOf("station" to (a["station"] as Int)), MATCH)
        val born = now
        var start = project(a.pos)
        var end = project(b.pos)
        var bend = 0.0
        var width = 1.0
        while (match.live) {
            val length = a.pos.distance(b.pos).toDouble()
            start = project(a.pos)
            end = project(b.pos)
            bend = 12 + 10 * sin((now - born) * 4 + a.id)
            width = 0.65 + 2.45 * ((length - 30) / 71).coerceIn(0.0, 1.0)
            val middle = midpoint(a.pos, b.pos)
            val dx = end.x - start.x
            val dy = end.y - start.y
            val projectedLength = hypot(dx.toDouble(), dy.toDouble()).coerceAtLeast(1.0)
            middle.add(vec(-dy * bend * 0.75 / projectedLength, dx * bend * 0.75 / projectedLength, 0))
            move(knot, middle)
            set(strand, "length", length)
            draw(bow(start, end, bend, stitchInk, width, rise(now - born, 0.42)))
            nextFrame()
        }
        val released = now
        while (now - released < departureFade) {
            val opacity = 1 - rise(now - released, departureFade)
            draw(
                bow(start, end, bend + 25 * (1 - opacity), stitchInk, width * (0.4 + 0.6 * opacity), opacity * opacity),
            )
            nextFrame()
        }
    }

    val neighbors = knots.pairsWithin(29.0, leave = 37.0).where { (a, b) -> a["station"] == b["station"] }
    onEnter("weave neighboring knots", neighbors) { (a, b) ->
        edge("weave", a, b, lifetime = MATCH)
        val born = now
        var ends = [project(a.pos), project(b.pos)]
        while (match.live) {
            ends = [project(a.pos), project(b.pos)]
            draw(spatialStroke(ends, weaveInk, width = 0.9, opacity = rise(now - born, 0.35)))
            nextFrame()
        }
        val released = now
        while (now - released < 0.48) {
            draw(spatialStroke(ends, weaveInk, width = 0.9, opacity = 1 - rise(now - released, 0.48)))
            nextFrame()
        }
    }

    val taut = edges("stitch").where { ((it["length"] as? Number)?.toDouble() ?: 0.0) > 73 }
    onEnter("echo a taut stitch", taut, condition = "Stitch length exceeds 73 units") { stitch ->
        edge("echo", stitch.from, stitch.to, lifetime = MATCH)
        val born = now
        var a = project(stitch.from.pos)
        var b = project(stitch.to.pos)
        var bend = -24.0
        while (match.live) {
            a = project(stitch.from.pos)
            b = project(stitch.to.pos)
            bend = -25 - 9 * sin(4 * (now - born))
            draw(bow(a, b, bend, echoInk, 1.1, rise(now - born, 0.32)))
            nextFrame()
        }
        val released = now
        while (now - released < 0.75) {
            val tail = rise(now - released, 0.75)
            draw(bow(a, b, bend - 16 * tail, echoInk, 1.1, 1 - tail))
            nextFrame()
        }
    }
}

// Fit an ellipse to the projected second moments of each station's committed knot cloud.
class Fit(val centre: Vec3, val angle: Double, val major: Double, val minor: Double)

fun fit(points: List<Vec3>): Fit {
    val cx = points.sumOf { it.x.toDouble() } / points.size
    val cy = points.sumOf { it.y.toDouble() } / points.size
    val xx = points.sumOf { (it.x - cx) * (it.x - cx) } / points.size
    val yy = points.sumOf { (it.y - cy) * (it.y - cy) } / points.size
    val xy = points.sumOf { (it.x - cx) * (it.y - cy) } / points.size
    val spread = hypot(xx - yy, 2 * xy)
    return Fit(
        vec(cx, cy, 0),
        0.5 * atan2(2 * xy, xx - yy),
        2 * sqrt(((xx + yy + spread) / 2).coerceAtLeast(0.0)) + 13,
        2 * sqrt(((xx + yy - spread) / 2).coerceAtLeast(0.0)) + 10,
    )
}

fun outline(fit: Fit, opacity: Double): FLine {
    val (ux, uy) = cos(fit.angle) to sin(fit.angle)
    val rim =
        List(65) { i ->
            val t = 2 * PI * i / 64
            val x = fit.major * cos(t)
            val y = fit.minor * sin(t)
            vec(fit.centre.x + ux * x - uy * y, fit.centre.y + uy * x + ux * y, 0)
        }
    return spatialStroke(rim, treeInk, width = 0.9, opacity = opacity)
}

world.source("fit and connect") {
    var previousTime = now
    val visible = DoubleArray(stations)
    val fits = arrayOfNulls<Fit>(stations)
    while (true) {
        val dt = (now - previousTime).coerceAtMost(0.10)
        previousTime = now
        val groups = world.points("knot").groupBy { it["station"] as Int }
        for (station in 0 until stations) {
            val cloud = groups[station].orEmpty()
            if (cloud.size >= 4) fits[station] = fit(cloud.map { project(it.pos) })
            visible[station] = (visible[station] + if (cloud.size >= 4) dt * 2.7 else -dt * 1.35).coerceIn(0.0, 1.0)
            val mark = fits[station]
            if (mark != null && visible[station] > 0) draw(outline(mark, visible[station] * 0.66))
        }
        val live = (0 until stations).filter { visible[it] > 0 && fits[it] != null }
        if (live.size >= 2) {
            val centres = live.map { fits[it]!!.centre }
            val tree = vec3MinimumSpanningTree(centres)
            for (edge in tree.edges) {
                val alpha = minOf(visible[live[edge.i]], visible[live[edge.j]])
                draw(ribbon(centres[edge.i], centres[edge.j], width = 2.2 + 1.8 * alpha, opacity = 0.53 * alpha))
            }
        }
        nextFrame()
    }
}

world.rules("inspect") {
    onEnter("show knot", points("knot").each()) { knot ->
        while (match.live) {
            val radius = if (inspected.get() == knot.id) 7.0 else 3.2
            draw(
                world.inspectOnClick(knot, spatialDot(project(knot.pos), radius, knotInk)) { why ->
                    inspected.set(why.id)
                    val inputs = why.inputs.joinToString { "${it.kind ?: "tip"} #${it.id}" }
                    val age = why.ageSeconds.f2
                    markdown(
                        "Why this knot?",
                        "**Knot #${why.id}** · $age s old\n\n**${why.origin.process}** from $inputs.\n\n${why.origin.condition}",
                    )
                },
            )
            nextFrame()
        }
    }
}

displayOpen("loom")
var lastPreview = Double.NEGATIVE_INFINITY
loop {
    val time = AnimationClock.nowSeconds()
    if (time - lastPreview + 1e-6 >= 1.0 / 30.0) {
        lastPreview = time
        lines(
            world.step(time),
            channel = "loom",
            w = 850.0,
            h = 370.0,
            fitToSize = true,
            framing = LinesFraming.Fixed(0.0, 35.0, 850.0, 370.0),
        )
    }
}.end { world.close() }
```

### A thousand moving points

Start with fifty rings of twenty points each. A traveling wave swells and contracts the tube
so that some samples come within 19 units of one another while others drift beyond 23.
Cyan threads join points in the same ring, and amber threads cross between rings. Each thread
has its own age and fades out when its match ends. The count under the drawing reports
committed graph edges, so a fading thread can still be visible after its edge has retracted.

This is the tutorial's [bond rule](#2-draw-a-line-between-nearby-seeds), unchanged in shape, run
over a thousand points. This box uses the same rule engine with parallel radius queries. It batches the tiny point
marks into one FLine and lets each matching process contribute its own thread.

```diffstatic_runner {data-lang=kotlin video="assets/spatial_rules/thousand.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="7" fps="18" w="1020" h="650" timeout="360" embed=true name="spatial-thousand-film" alt="A rotating torus of a thousand pale samples, with waves of dense cyan and amber proximity threads fading in and out as the tube swells. A readout shows the live point and edge counts."}
import afield.data.graph.SpatialLifetime.MATCH

val rings = 50
val samples = 20
val world = SpatialRuleWorld(parallelSearch = true)
val filamentInk = vec(0.43, 0.83, 1.0, 0.66)
val bandInk = vec(1.0, 0.66, 0.29, 0.64)
val pointInk = vec(0.84, 0.92, 1.0, 0.47)

fun surface(i: Int, j: Int, t: Double): Vec3 {
    val u = 2 * PI * i / rings
    val v = 2 * PI * j / samples
    val wave = cos(3 * u - 1.8 * t)
    val tube = 47 + 24 * wave + 7 * sin(2 * v + t)
    val radius = 166 + tube * cos(v)
    return vec(radius * cos(u), tube * sin(v) + 22 * sin(2 * u + t), radius * sin(u))
}

val start = AnimationClock.nowSeconds()
fun project(p: Vec3, t: Double): Vec3 {
    val turn = 0.43 * t
    val x = p.x * cos(turn) - p.z * sin(turn)
    val z = p.x * sin(turn) + p.z * cos(turn)
    val tilt = 0.62 + 0.22 * sin(t * 0.62)
    val y = p.y * cos(tilt) - z * sin(tilt)
    val depth = p.y * sin(tilt) + z * cos(tilt)
    val zoom = 910 / (910 + depth)
    return vec(410 + x * zoom, 241 + y * zoom, 0)
}

world.source("a thousand moving points") {
    val anchors =
        List(rings * samples) { n ->
            val i = n / samples
            val j = n % samples
            point("sample", surface(i, j, 0.0), mapOf("band" to i))
        }
    while (true) {
        val t = now - start
        val marks = FLine()
        for ((n, anchor) in anchors.withIndex()) {
            move(anchor, surface(n / samples, n % samples, t))
            val p = project(anchor.pos, t)
            marks.moveTo(p.x - 0.8, p.y).lineTo(p.x + 0.8, p.y)
        }
        marks.color = pointInk
        marks.thickness = 1.45
        draw(marks)
        nextFrame()
    }
}

world.rules("surface links") {
    val nearby = points("sample").pairsWithin(19.0, leave = 23.0)
    onEnter("thread nearby samples", nearby) { (a, b) ->
        edge("thread", a, b, lifetime = MATCH)
        val ink = if (a["band"] == b["band"]) filamentInk else bandInk
        val born = now
        var start3 = a.pos
        var end3 = b.pos
        while (match.live) {
            start3 = a.pos
            end3 = b.pos
            val t = now - start
            val alpha = ((now - born) / 0.33).coerceIn(0.0, 1.0)
            val width = 0.65 + 0.9 * (1 - start3.distance(end3) / 23.0).coerceIn(0.0, 1.0)
            draw(spatialStroke([project(start3, t), project(end3, t)], ink, width, alpha))
            nextFrame()
        }
        val released = now
        while (now - released < 0.55) {
            val t = now - start
            val alpha = 1 - (now - released) / 0.55
            draw(spatialStroke([project(start3, t), project(end3, t)], ink, 0.7, alpha * alpha))
            nextFrame()
        }
    }
}

displayOpen("torus")
var lastPreview = Double.NEGATIVE_INFINITY
var lastReadout = Double.NEGATIVE_INFINITY
loop {
    val time = AnimationClock.nowSeconds()
    if (time - lastPreview + 1e-6 >= 1.0 / 24.0) {
        lastPreview = time
        val drawing = world.step(time)
        lines(
            drawing,
            channel = "torus",
            w = 820.0,
            h = 480.0,
            fitToSize = true,
            framing = LinesFraming.Fixed(35.0, 18.0, 750.0, 444.0),
        )
        val threads = world.edges("thread").size
        if (threads > 0 && time - lastReadout >= 0.5) {
            lastReadout = time
            markdown(
                "Counts",
                "${world.points("sample").size} moving samples  ·  $threads live threads",
            )
        }
    }
}.end { world.close() }
```

### Ropes that go slack

Four ropes hang along the top of the drawing, out of step with one another. For each, three or
four anchors arrive one at a time, and the rope is hung once they are all there. Then the anchors
let go one by one, in an order that changes every round. A rope pinned in three places slumps
into longer arcs as each pin goes, hangs from a single anchor, and only falls once the last one
has gone. A second later the next anchors start to arrive.

Each place is its own source, `"anchors 0"` to `"anchors 3"`, and each publishes its rope with
`link("rope", anchors)`. A *link* joins any number of points. An edge goes as soon as either of
its ends does, but a link stays while any of its ends is still published, and `rope.attached`
lists the ones that are. So `links("rope")` keeps its match until the last anchor has gone.

The rope's process is a chain of particles. Each frame, gravity pulls them, the particles at the
rope's live ends follow their anchors, and forty passes pull every segment back to its length,
which keeps the rope from stretching. A weaker pull across each pair of segments resists bending.
The sources and the rule share no variables: a rope finds out that an anchor has gone because the
anchor's `alive` turns false. Like the beads in [step 6](#6-when-a-bond-breaks-let-its-beads-fall),
a rope outlives its match, falling for two seconds and fading. Try a `slack` of 1.05 for ropes
pulled nearly tight.

Each rope's physics touches only its own particles, so the rule passes `parallel = true` and the
ropes advance at the same time on several threads. What they draw comes out in the same order
every run; [Run processes in parallel](#run-processes-in-parallel) says what a parallel process
may and may not share.

```diffstatic_runner {data-lang=kotlin video="assets/spatial_rules/rope.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="12" fps="20" w="940" h="540" timeout="300" embed=true name="spatial-rope-film" alt="Pale ropes hang in arcs between swaying amber anchors along the top of a wide drawing, crossing one another. Anchors arrive one at a time before each rope appears, then vanish one by one, so each rope slumps into longer arcs, dangles from its last anchor and falls away. A Ropes panel counts the ropes and the anchors holding them."}
import kotlin.random.Random

val world = SpatialRuleWorld()
val slack = 1.3
val anchorInk = vec(1.0, 0.75, 0.4, 1.0)
val ropeInk = vec(0.85, 0.9, 1.0, 1.0)

/*
Four places along the top hang ropes, out of step with one another. In each, three or four
anchors arrive one at a time and the rope is hung once they are all there. Then they let go one
by one, in an order that changes every time, and a second later the next anchors start to arrive.
*/
for (slot in 0 until 4) world.source("anchors $slot") {
    wait(1.3 * slot)
    var round = 0
    while (true) {
        val count = 3 + (slot + round) % 2
        val left = 60.0 + 190 * slot
        val homes = count { k -> vec(left + k * 240.0 / (count - 1), 50 + 20 * sin(2.0 * k + round), 0) }
        val held = linkedMapOf<SpatialPoint, Vec3>()
        fun sway() {
            for ((anchor, home) in held) {
                move(anchor, home + vec(20 * sin(0.8 * age + slot), 6 * sin(1.3 * age + home.x / 50), 0))
                draw(spatialDot(anchor.pos, 4.0, anchorInk))
            }
        }
        for (home in homes) {
            held[point("anchor", home)] = home
            during(0.4) { sway() }
        }
        link("rope", held.keys.toList())
        for (anchor in held.keys.shuffled(Random(10 * slot + round))) {
            during(1.2 + 0.8 * ((slot + round) % 3)) { sway() }
            held -= anchor
            retract(anchor)
        }
        wait(1.0)
        round++
    }
}

/*
A rope is a chain of particles. Each frame gravity pulls them, the particles at the rope's live
ends follow their anchors, and repeated passes pull every segment back to its length. A weaker
pull across each pair of segments resists bending, which is what makes the rope stiff.
*/
world.rules("ropes") {
    onEnter("hang a rope", links("rope"), parallel = true) { rope ->
        val n = 48
        val pins = rope.ends.indices.map { it * (n - 1) / (rope.ends.size - 1) }
        val start = rope.ends.map { it.pos }
        // Start each span already hanging, as a parabola about as long as the rope it holds.
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val along = i.toDouble() * (start.size - 1) / (n - 1)
            val k = min(along.toInt(), start.size - 2)
            val f = along - k
            val span = start[k].distance(start[k + 1]).toDouble()
            x[i] = start[k].x + (start[k + 1].x - start[k].x) * f
            y[i] = start[k].y + (start[k + 1].y - start[k].y) * f + 4 * f * (1 - f) * span * sqrt(3 * (slack - 1) / 8)
        }
        val px = x.copyOf()
        val py = y.copyOf()
        val segment = slack * start.zipWithNext { a, b -> a.distance(b).toDouble() }.sum() / (n - 1)
        val pinned = BooleanArray(n)

        fun pull(i: Int, j: Int, length: Double, stiffness: Double) {
            val dx = x[j] - x[i]
            val dy = y[j] - y[i]
            val d = max(hypot(dx, dy), 1e-6)
            val wi = if (pinned[i]) 0.0 else 1.0
            val wj = if (pinned[j]) 0.0 else 1.0
            if (wi + wj == 0.0) return
            val k = stiffness * (d - length) / d / (wi + wj)
            x[i] += wi * k * dx
            y[i] += wi * k * dy
            x[j] -= wj * k * dx
            y[j] -= wj * k * dy
        }

        var last = now
        fun simulate() {
            val dt = (now - last).coerceIn(0.0, 0.05)
            last = now
            pinned.fill(false)
            for ((end, i) in rope.ends.zip(pins)) if (end.alive) {
                pinned[i] = true
                x[i] = end.pos.x.toDouble()
                y[i] = end.pos.y.toDouble()
                px[i] = x[i]
                py[i] = y[i]
            }
            for (i in 0 until n) if (!pinned[i]) {
                val vx = (x[i] - px[i]) * 0.995
                val vy = (y[i] - py[i]) * 0.995
                px[i] = x[i]
                py[i] = y[i]
                x[i] += vx
                y[i] += vy + 900 * dt * dt
            }
            repeat(40) {
                for (i in 0 until n - 1) pull(i, i + 1, segment, 1.0)
                for (i in 0 until n - 2) pull(i, i + 2, 2 * segment, 0.08)
            }
        }

        fun shape() = n { vec(x[it], y[it], 0) }

        whileLive {
            simulate()
            draw(spatialStroke(shape(), ropeInk, width = 2.0))
        }
        // The last anchor has let go: the rope falls free and fades.
        during(2.0) { t ->
            simulate()
            draw(spatialStroke(shape(), ropeInk, width = 2.0, opacity = 1 - t))
        }
    }
}

play(
    world,
    w = 900.0,
    h = 400.0,
    after = {
        val ropes = world.links("rope")
        markdown("Ropes", "${ropes.size} ropes holding ${ropes.sumOf { it.attached.size }} anchors")
    },
)
```

With twenty-four places packed along the top instead of four, the ropes overlap into a curtain
that is always partly hung and partly falling. This is where running in parallel starts to pay.
In a test of the same physics on a laptop with eight fast cores, four ropes took 0.4 ms a frame
one after another and 0.3 ms in parallel; 32 ropes took 3.1 ms and 0.6 ms; 128 took 12 ms and
2.5 ms.

```diff_runner {data-lang=kotlin video="assets/spatial_rules/rope_curtain.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="12" fps="20" w="940" h="540" timeout="300" embed=true name="spatial-rope-curtain" alt="Dozens of pale ropes hang in overlapping arcs from amber anchors packed along the top of a wide drawing, some slumping as anchors let go and some falling away, while new anchors arrive. A Ropes panel counts the ropes and the anchors holding them."}
:::after
import kotlin.random.Random

val world = SpatialRuleWorld()
val slack = 1.3
val anchorInk = vec(1.0, 0.75, 0.4, 1.0)
val ropeInk = vec(0.85, 0.9, 1.0, 1.0)

/*
Twenty-four places along the top hang ropes, out of step with one another. In each, three or four
anchors arrive one at a time and the rope is hung once they are all there. Then they let go one
by one, in an order that changes every time, and a second later the next anchors start to arrive.
*/
for (slot in 0 until 24) world.source("anchors $slot") {
    wait(0.35 * slot)
    var round = 0
    while (true) {
        val count = 3 + (slot + round) % 2
        val left = 30.0 + 26 * slot
        val homes = count { k -> vec(left + k * 240.0 / (count - 1), 50 + 20 * sin(2.0 * k + round), 0) }
        val held = linkedMapOf<SpatialPoint, Vec3>()
        fun sway() {
            for ((anchor, home) in held) {
                move(anchor, home + vec(20 * sin(0.8 * age + slot), 6 * sin(1.3 * age + home.x / 50), 0))
                draw(spatialDot(anchor.pos, 4.0, anchorInk))
            }
        }
        for (home in homes) {
            held[point("anchor", home)] = home
            during(0.4) { sway() }
        }
        link("rope", held.keys.toList())
        for (anchor in held.keys.shuffled(Random(10 * slot + round))) {
            during(1.2 + 0.8 * ((slot + round) % 3)) { sway() }
            held -= anchor
            retract(anchor)
        }
        wait(1.0)
        round++
    }
}

/*
A rope is a chain of particles. Each frame gravity pulls them, the particles at the rope's live
ends follow their anchors, and repeated passes pull every segment back to its length. A weaker
pull across each pair of segments resists bending, which is what makes the rope stiff.
*/
world.rules("ropes") {
    onEnter("hang a rope", links("rope"), parallel = true) { rope ->
        val n = 48
        val pins = rope.ends.indices.map { it * (n - 1) / (rope.ends.size - 1) }
        val start = rope.ends.map { it.pos }
        // Start each span already hanging, as a parabola about as long as the rope it holds.
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val along = i.toDouble() * (start.size - 1) / (n - 1)
            val k = min(along.toInt(), start.size - 2)
            val f = along - k
            val span = start[k].distance(start[k + 1]).toDouble()
            x[i] = start[k].x + (start[k + 1].x - start[k].x) * f
            y[i] = start[k].y + (start[k + 1].y - start[k].y) * f + 4 * f * (1 - f) * span * sqrt(3 * (slack - 1) / 8)
        }
        val px = x.copyOf()
        val py = y.copyOf()
        val segment = slack * start.zipWithNext { a, b -> a.distance(b).toDouble() }.sum() / (n - 1)
        val pinned = BooleanArray(n)

        fun pull(i: Int, j: Int, length: Double, stiffness: Double) {
            val dx = x[j] - x[i]
            val dy = y[j] - y[i]
            val d = max(hypot(dx, dy), 1e-6)
            val wi = if (pinned[i]) 0.0 else 1.0
            val wj = if (pinned[j]) 0.0 else 1.0
            if (wi + wj == 0.0) return
            val k = stiffness * (d - length) / d / (wi + wj)
            x[i] += wi * k * dx
            y[i] += wi * k * dy
            x[j] -= wj * k * dx
            y[j] -= wj * k * dy
        }

        var last = now
        fun simulate() {
            val dt = (now - last).coerceIn(0.0, 0.05)
            last = now
            pinned.fill(false)
            for ((end, i) in rope.ends.zip(pins)) if (end.alive) {
                pinned[i] = true
                x[i] = end.pos.x.toDouble()
                y[i] = end.pos.y.toDouble()
                px[i] = x[i]
                py[i] = y[i]
            }
            for (i in 0 until n) if (!pinned[i]) {
                val vx = (x[i] - px[i]) * 0.995
                val vy = (y[i] - py[i]) * 0.995
                px[i] = x[i]
                py[i] = y[i]
                x[i] += vx
                y[i] += vy + 900 * dt * dt
            }
            repeat(40) {
                for (i in 0 until n - 1) pull(i, i + 1, segment, 1.0)
                for (i in 0 until n - 2) pull(i, i + 2, 2 * segment, 0.08)
            }
        }

        fun shape() = n { vec(x[it], y[it], 0) }

        whileLive {
            simulate()
            draw(spatialStroke(shape(), ropeInk, width = 2.0))
        }
        // The last anchor has let go: the rope falls free and fades.
        during(2.0) { t ->
            simulate()
            draw(spatialStroke(shape(), ropeInk, width = 2.0, opacity = 1 - t))
        }
    }
}

play(
    world,
    w = 900.0,
    h = 400.0,
    after = {
        val ropes = world.links("rope")
        markdown("Ropes", "${ropes.size} ropes holding ${ropes.sumOf { it.attached.size }} anchors")
    },
)
```

### A tree that keeps its branches

Seeds drift across the drawing, and none of them stays long. Forty places each hold one seed at
a time: it stays for six to twelve seconds, fades, and a moment later a new one appears somewhere
else. Green branches join whichever seeds are there in a minimum spanning tree, the shortest set
of branches that connects every seed. The tree is recomputed every frame, but a branch that stays
in it keeps its process, so only the branches that change grow in or fade out. A new seed grows a
branch to its nearest part of the tree. When a seed goes, its branches fade, and new ones grow to
join the pieces it held together.

No built-in pattern describes a spanning tree, so the rule computes one. `pairs { }` makes a
pattern from any calculation that returns pairs of points. It reads this frame's facts, here
every pair an ordinary `pairsWithin` pattern matches as `matches(near)`, and returns the tree's
branches. `minimumSpanningForest` finds the tree from weighted candidates. A pair that was in last
frame's tree is the same match this frame, so its process carries on. A new pair starts a process.
A pair that has dropped out of the tree, or lost one of its seeds, ends its match, so its branch
fades. Nothing in the rule handles arrivals or departures: they are just the facts changing
under the same calculation.

`previous` is what the pattern returned last frame. Weighting a standing branch at `keep` times
its length makes the tree hold its shape until a change gains it more than that, the same idea as
`leave` in [step 3](#3-join-close-let-go-far), written as part of the calculation. The counter
shows how many seeds are there and how many branches have grown so far.

```diffstatic_runner {data-lang=kotlin video="assets/spatial_rules/tree.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="20" fps="20" w="940" h="540" timeout="300" embed=true name="spatial-tree" alt="Pale seeds appear one after another across a wide drawing and green branches grow between them into one spanning tree. Later, seeds fade out here and there, their branches fade with them, and new branches grow to rejoin the tree. A Tree panel counts the seeds, the branches standing and the branches grown so far."}
val world = SpatialRuleWorld()
val w = 900.0
val h = 400.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val branchInk = vec(0.55, 0.95, 0.7, 1.0)

fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun toward(a: Vec3, b: Vec3, t: Double) = vec(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, 0)

var here = 0

// Forty places each hold one seed at a time. A seed stays six to twelve seconds, fades, and
// a second or two later a new one appears somewhere else.
for (place in 0 until 40) world.source("seeds $place") {
    wait(0.15 * place)
    var round = 0
    while (true) {
        val i = place + 40 * round
        val seed = point("seed", wander(i, now))
        here++
        val born = now
        during(6.0 + 6.0 * (i * 0.618 % 1.0)) {
            move(seed, wander(i, now))
            draw(spatialDot(seed.pos, 3.0, ink, opacity = min(1.0, (now - born) / 0.5)))
        }
        val last = seed.pos
        retract(seed)
        here--
        during(0.6) { t -> draw(spatialDot(last, 3.0, ink, opacity = 1 - t)) }
        wait(0.4 + i * 0.382 % 1.0)
        round++
    }
}

// A branch already in the tree counts as `keep` times its length, so the tree only changes
// shape when the change gains it more than that.
val keep = 0.75
var standing = 0
var grown = 0

world.rules("tree") {
    val near = points("seed").pairsWithin(220.0)
    val tree = pairs { previous ->
        val seeds = points("seed")
        val index = seeds.withIndex().associate { (i, seed) -> seed to i }
        val before = previous.toSet()
        val candidates =
            matches(near).map { (a, b) ->
                GraphEdge(index.getValue(a), index.getValue(b), a.distance(b) * if (a to b in before) keep else 1.0)
            }
        minimumSpanningForest(seeds.size, candidates).map { seeds[it.i] to seeds[it.j] }
    }
    onEnter("grow a branch", tree) { (a, b) ->
        standing++
        grown++
        whileLive { draw(spatialStroke([a.pos, toward(a.pos, b.pos, min(1.0, age / 0.4))], branchInk, width = 1.5)) }
        standing--
        during(0.6) { t -> draw(spatialStroke([a.pos, b.pos], branchInk, width = 1.5, opacity = 1 - t)) }
    }
}

play(world, w = w, h = h, after = { markdown("Tree", "$here seeds, $standing branches standing, $grown grown so far") })
```

With `keep = 1.0`, the tree is the plain minimum spanning tree every frame. Run it for the same
twenty seconds as the film and the count of branches grown climbs much faster, as near-equal
branches trade places.

```diff_runner {data-lang=kotlin screenshot="assets/spatial_rules/tree_no_keep.png" capture="box" execution="launch" wait="20" open-outputs="true" w="940" h="540" timeout="240" embed=true name="spatial-tree-no-keep" alt="The same spanning tree of drifting seeds, with a higher count of branches grown in the Tree panel."}
:::after
val world = SpatialRuleWorld()
val w = 900.0
val h = 400.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val branchInk = vec(0.55, 0.95, 0.7, 1.0)

fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

fun toward(a: Vec3, b: Vec3, t: Double) = vec(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, 0)

var here = 0

// Forty places each hold one seed at a time. A seed stays six to twelve seconds, fades, and
// a second or two later a new one appears somewhere else.
for (place in 0 until 40) world.source("seeds $place") {
    wait(0.15 * place)
    var round = 0
    while (true) {
        val i = place + 40 * round
        val seed = point("seed", wander(i, now))
        here++
        val born = now
        during(6.0 + 6.0 * (i * 0.618 % 1.0)) {
            move(seed, wander(i, now))
            draw(spatialDot(seed.pos, 3.0, ink, opacity = min(1.0, (now - born) / 0.5)))
        }
        val last = seed.pos
        retract(seed)
        here--
        during(0.6) { t -> draw(spatialDot(last, 3.0, ink, opacity = 1 - t)) }
        wait(0.4 + i * 0.382 % 1.0)
        round++
    }
}

// A branch already in the tree counts as `keep` times its length, so the tree only changes
// shape when the change gains it more than that.
val keep = 1.0
var standing = 0
var grown = 0

world.rules("tree") {
    val near = points("seed").pairsWithin(220.0)
    val tree = pairs { previous ->
        val seeds = points("seed")
        val index = seeds.withIndex().associate { (i, seed) -> seed to i }
        val before = previous.toSet()
        val candidates = matches(near).map { (a, b) ->
            GraphEdge(index.getValue(a), index.getValue(b), a.distance(b) * if (a to b in before) keep else 1.0)
        }
        minimumSpanningForest(seeds.size, candidates).map { seeds[it.i] to seeds[it.j] }
    }
    onEnter("grow a branch", tree) { (a, b) ->
        standing++
        grown++
        whileLive { draw(spatialStroke([a.pos, toward(a.pos, b.pos, min(1.0, age / 0.4))], branchInk, width = 1.5)) }
        standing--
        during(0.6) { t -> draw(spatialStroke([a.pos, b.pos], branchInk, width = 1.5, opacity = 1 - t)) }
    }
}

play(world, w = w, h = h, after = { markdown("Tree", "$here seeds, $standing branches standing, $grown grown so far") })
```

### Shapes pinned between points

Fourteen posts lean and slide along the ground. Each is an edge from a foot to a head, drawn as a
spindle skinned onto it. The spindle is drawn once, in a bind pose, lying between two bind
points, (0, 0) and (200, 0). Each post makes its own [`SkinnedLine`](reference/graphics.md#flineskin-kt-skinnedline). On every frame,
[`pose`](reference/graphics.md#flineskin-kt--skinnedline--pose) turns the spindle the shortest way from the bind direction to the edge's, stretches it
along the edge by whatever it takes to reach both ends, and moves it into place. Across the edge
it stretches less: with `across = 0.5` its width grows by the square root of its stretch along,
so a post thins a little as it lengthens and fattens as it shortens.

`lag` makes the posed spindle follow the edge with a delay that varies along it. It is given each
point's place between the bind points, from 0 to 1, and returns a time in seconds: here none at
the ends and a third of a second in the middle, so the ends stay pinned to the foot and head
while the middle trails behind them, and a post bows as it leans. A lagging skin remembers where
its points were, so each post's process makes its own.

When two feet come within 80 units of each other, a woven panel is pinned between the two posts
by its four corners with [`spatialSkin(shape, corners)`](reference/data.md#spatial-rule-drawing-kt--spatialskin), so it shears and twists as the posts
lean. A foot carries its head in its data, which is how the panel finds its top corners.

Every post and every panel is a copy of its shape, worked out point by point each frame, so both
rules pass `parallel = true`. The panel counter is shared by all the panel processes, which may
now run at the same moment, so it is `0.atomic`: `panels += 1` and `panels -= 1` are each one
indivisible step, where `+= 1` on a plain `var` could lose a count.

```diffstatic_runner {data-lang=kotlin video="assets/spatial_rules/skins.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="8" fps="15" w="940" h="540" timeout="300" embed=true name="spatial-skins" alt="Pale spindle-shaped posts lean, slide and bow along the bottom of a wide drawing, their middles trailing their ends. Amber woven panels appear between neighbouring posts when their feet come close, shearing and twisting as the posts lean, and fade away as the feet part. A Fence panel counts the panels."}
val world = SpatialRuleWorld()
val ink = vec(0.85, 0.9, 1.0, 1.0)
val weave = vec(1.0, 0.75, 0.4, 1.0)

// The shapes are drawn once. The spindle lies between its bind points, (0, 0) and (200, 0);
// a panel's four corners are the corners of its bounds.
val spindle = FLine().apply {
    moveTo(0, 0)
    cubicTo(60, -14, 140, -14, 200, 0)
    cubicTo(140, 14, 60, 14, 0, 0)
    color = ink
    filled = true
    stroked = false
}
val panel = FLine().apply {
    for (i in 0..4) {
        moveTo(i / 4.0, 0)
        lineTo(i / 4.0, 1)
    }
    for (j in 0..3) {
        moveTo(0, j / 3.0)
        lineTo(1, j / 3.0)
    }
    color = weave
}

fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

world.source("posts") {
    val posts = 14 { i ->
        val head = point("head", vec(60 + 60 * i, 120, 0))
        val foot = point("foot", vec(60 + 60 * i, 330, 0), data = mapOf("head" to head))
        edge("post", foot, head)
        foot to head
    }
    while (true) {
        for ((i, post) in posts.withIndex()) {
            val (foot, head) = post
            val x = 450 + 400 * zigzag((0.1 + 0.03 * (i % 4)) * age + 1.7 * i)
            move(foot, vec(x, 330 + 15 * sin(0.4 * age + i), 0))
            move(head, vec(x + 60 * sin(0.6 * age + 2.1 * i), 110 + 40 * sin(0.5 * age + 1.3 * i), 0))
        }
        nextFrame()
    }
}

val panels = 0.atomic

world.rules("fence") {
    onEnter("stand a post", edges("post"), parallel = true) { post ->
        val skin = SkinnedLine(spindle, vec(0, 0, 0), vec(200, 0, 0), across = 0.5, lag = { s -> 0.35 * sin(PI * s) })
        whileLive { draw(skin.pose(post, now)) }
    }
    onEnter("weave a panel", points("foot").pairsWithin(80.0, leave = 100.0), parallel = true) { (a, b) ->
        panels += 1
        // The corners go round the shape's bounds: its two feet first, then the heads above them.
        fun corners() = [a.pos, b.pos, (b["head"] as SpatialPoint).pos, (a["head"] as SpatialPoint).pos]
        whileLive { draw(spatialSkin(panel, corners(), opacity = min(1.0, age / 0.5))) }
        panels -= 1
        during(0.5) { t -> draw(spatialSkin(panel, corners(), opacity = 1 - t)) }
    }
}

play(world, w = 900.0, h = 400.0, after = { markdown("Fence", "$panels panels") })
```

## Publishing and matching

Create a `SpatialRuleWorld` once, install one or more named `source` and `rules` blocks, then
end the box with [`play(world)`](reference/data.md#spatial-rule-drawing-kt--play). `play` steps the world on the animation clock at up to `fps`
frames a second, draws the result into `channel` at `w` by `h`, calls `after` with each frame's
lines, passes presses on the drawing to `onPress` between frames, and closes the world when the
box stops. To draw somewhere else, call `world.step(AnimationClock.nowSeconds())` from your own
`loop`, pass the returned FLines to `lines` or another drawing surface, and call `world.close()`
from the loop’s `.end { }`. Inside a process, `now` is that step’s time in seconds and `age` is
the time since the process started.

A source can publish `point("tip", at, data = mapOf("row" to 0))` and then move it with
`move(tip, to)`. The owner can revise any of its publications with `set(fact, key, value)`
or remove one with `retract(fact)`. Passing `null` to `set` removes the key. Readers use
`fact.data` or `fact["row"]`. Use values you can treat as immutable across frames.
`point.pos` returns a copy.

- `points("tip").each()` binds individual points. `pairsWithin` binds unordered pairs once.
  `points("tip").near(points("anchor"), enter)` binds ordered roles.
- `edges("bond").triangles()` binds each triangle that edges of that kind close, ignoring
  their direction, as a `SpatialTriangle` with points `a`, `b`, `c` in ID order and edges
  `ab`, `bc`, `ca`. Each side uses the lowest-ID edge of its kind. If that edge goes, the
  match ends and a parallel edge can start a new one.
- `links("rope")` binds each link, a fact a process publishes with `link(kind, ends)` to join any
  number of points. A link stays while any end is published; `attached` lists those that are.
- `edges("stitch")` binds each individual edge. `.where { ... }` filters a pattern using
  Kotlin, including arbitrary data from its bound points or edges. Keep predicates free of
  side effects. Share a pattern value when several rules need the same result.
- `onEnter` starts once per binding. A process that returns while its match is still live
  can start again after the binding leaves and re-enters. Give `onEnter` a `condition` label
  to explain a predicate written in Kotlin.
- Publications normally last until the process returns. Set `lifetime = SpatialLifetime.MATCH`
  when a publication should retract after the match ends, even while its drawing fades.
  An edge also retracts if either endpoint is gone. Check `point.alive` for a process that
  should follow an endpoint past the end of a proximity match.
- `.chance(p)` keeps each binding with probability `p`, decided once for that binding, so the
  same pair gets the same answer every frame and again if it comes back. A random number in
  `.where` would be drawn anew every frame.
- `computed(identity) { previous -> ... }` makes a pattern from any calculation over this frame's
  facts, and `pairs { previous -> ... }` does the same for unordered pairs of points. Inside,
  `points`, `edges`, `links` and `matches(pattern)` read the frame, and `previous` is what the
  calculation returned last frame, with the first value kept for each identity. A value whose
  facts were bound last frame keeps its process. [The tree](#a-tree-that-keeps-its-branches)
  uses `onEnter` to capture the entry value, a pair of live point handles. Use `onMatch` and
  read `binding.value` each frame when the computed value itself changes.
- `onEnter(name, pattern, parallel = true)` advances that rule's processes at the same time on
  several threads; see [Run processes in parallel](#run-processes-in-parallel).
- `whileLive { }` runs its block once a frame while the match holds; `whileLive()` just waits
  for the match to end. `during(seconds) { progress -> }` runs its block once a frame for that
  long, with `progress` going from 0 toward 1. `wait(seconds)` lets time pass.
- Each step commits the previous step’s publications, evaluates rules, and advances each
  process once. Downstream rules see changes on the following step. An FLine submitted with
  `draw` belongs to the current frame. Submit it again to keep it visible.
- Reinstall a named rule group to restart it while other sources and groups carry on.
  `stop(name)` ends that group immediately and queues retraction of its publications.

A point has a `velocity`, in units a second, which the world works out from the point's last two
positions, so a rule can compare directions of motion without its source publishing them.

An edge has a `length`, a position `at(t)` a fraction of the way from `from` to `to`, and the
`other(end)`. A point has `distance(other)`. To draw them, `spatialStroke` takes a list of
positions or an edge, `spatialFill` a list of positions or a triangle, and `spatialDot` a
position; each takes a colour and an `opacity`. `spatialSkin(shape, from, to, width)`, or
`spatialSkin(shape, edge, width)`, returns a copy of an FLine pinned between two points, and
`spatialSkin(shape, corners)` one pinned between four. `SkinnedLine(shape, bindFrom, bindTo,
across, lag)` holds a curve drawn in a bind pose, and `pose(from, to, now)` or `pose(edge, now)`
turns and stretches it onto live points, scaling it less across than along and letting parts of
it lag; see [shapes pinned between points](#shapes-pinned-between-points).

To find out why something is there, `world.nearest(at, within)` returns the live point, edge
or link closest to a position. `world.lineage(fact)` walks live ancestry, nearest first.
`world.explain(fact)` gives you the producer, input kinds and IDs, age, and live status of a
fact you hold. `world.fact(id)` looks up live facts by ID.

Pass your drawing through `world.inspectOnClick(fact, line) { why -> ... }` to pick the
actual drawn geometry and get its explanation from the frame it was drawn. Keep the fact
handle through the fade so you can still inspect the drawing after the match and its
inputs have gone. [One filament](#follow-two-points-then-fade) uses the same callback in
both phases.

### Follow a computed value

Each seed has a stable identity. Its speed is a new number each frame. This rule holds one
process per seed and reads the current speed from `binding.value` to set the width of its
halo. After the match ends, the binding retains the last matched value.

```diffstatic {data-lang=kotlin}
val world = SpatialRuleWorld()
val ink = vec(0.65, 0.88, 1.0, 1.0)

world.source("moving seed") {
    val seed = point("seed", vec(160, 90, 0))
    while (true) {
        move(seed, vec(160 + 105 * sin(age * 2), 90, 0))
        nextFrame()
    }
}

world.rules("speed") {
    val speed = computed<Pair<SpatialPoint, Double>>(identity = { listOf(it.first) }) {
        points("seed").map { it to it.velocity.length().toDouble() }
    }
    onMatch("show speed", speed) { binding ->
        whileLive {
            val (seed, unitsPerSecond) = binding.value
            draw(spatialDot(seed.pos, 3 + 0.06 * unitsPerSecond, ink))
        }
    }
}

play(world, w = 320.0, h = 180.0)
```

### Motion, direction and chance

Conditions combine with `.where` and `.chance`. Here lines drift sideways, some one way and some
the other. A rule over `edges("line")` keeps those within about 17 degrees of vertical and
publishes a point at each one's middle, which follows the line until it tips too far. A second
rule matches pairs of those middles that are within 80 units of each other, moving in opposite
directions (their velocities point apart, so the dot product is negative), and that won a coin
flip made once for that pair. Make the direction test stricter with
`a.velocity.dot(b.velocity) < -0.8 * a.velocity.length() * b.velocity.length()`.

```diffstatic_runner {data-lang=kotlin video="assets/spatial_rules/opposed.mp4" capture="box" execution="launch" wait-output="true" open-outputs="true" duration="12" fps="20" w="640" h="300" timeout="240" embed=true name="spatial-opposed" alt="Grey near-vertical lines drift sideways, some one way and some the other, leaning as they go. When two upright lines moving in opposite directions pass close by, a red stroke joins their middles for some pairs and not others, and lets go as they part or tip over."}
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
world.source("lines") {
    val ends = 16 { i -> point("end", vec(35 * i, 60, 0)) to point("end", vec(35 * i, 120, 0)) }
    for ((a, b) in ends) edge("line", a, b)
    while (true) {
        for ((i, pair) in ends.withIndex()) {
            val x = 300 + 250 * sin(0.5 * age * (if (i % 2 == 0) 1 else -1) + i)
            move(pair.first, vec(x, 60 + 3 * i, 0))
            move(pair.second, vec(x + 15 * sin(age + i), 120 + 3 * i, 0))
            draw(spatialStroke([pair.first.pos, pair.second.pos], vec(0.6, 0.6, 0.6, 1)))
        }
        nextFrame()
    }
}

// An edge rule publishes a point at the middle of each near-vertical line, and keeps it there.
world.rules("middles") {
    val upright = edges("line").where { abs(it.from.pos.x - it.to.pos.x) < 0.3 * abs(it.from.pos.y - it.to.pos.y) }
    onEnter("mark the middle", upright) { line ->
        val middle = point("middle", line.at(0.5), lifetime = MATCH)
        whileLive { move(middle, line.at(0.5)) }
    }
}

// Pairs of middles that are close, moving in opposite directions, and won a coin flip made once per pair.
world.rules("meetings") {
    val opposed =
        points("middle").pairsWithin(80.0, leave = 100.0).where { (a, b) -> a.velocity.dot(b.velocity) < 0 }.chance(0.5)
    onEnter("meet", opposed) { (a, b) ->
        whileLive { draw(spatialStroke([a.pos, b.pos], vec(1.0, 0.4, 0.3, 1), width = 2.5)) }
    }
}

play(world, w = 600.0, h = 200.0)
```

## Scaling up

The default neighbor index uses Field’s 3D spatial hash. It rebuilds a grid for each distinct
combination of point kinds and search radius used on a frame. Nearby candidates are shared
across rules with the same spatial pattern. Stable match identities preserve coroutine state
through the rebuild.

`SpatialRuleWorld(parallelSearch = true)` distributes radius queries for clouds with at least
256 left-side points across the JVM’s common fork-join pool. Index construction and Kotlin
predicates run on the caller’s thread. A custom `SpatialIndexFactory` can supply another
read-only neighbor index. An index used with parallel search must support concurrent queries
and return each neighbor at most once in deterministic order.

When measuring a piece, use `world.step(time) { timing -> ... }` in your loop to see how much
wall time goes to commits, index construction, matching, process updates, and collecting the
lines. Index time is part of matching. FLine construction inside a rule is part of its process
time. The display call after `step` has its own cost.

Cost depends on how many matches exist as well as how many points there are. A thousand
widely distributed points can have a few hundred active pairs. A thousand points all inside
one another’s radius can produce 499,500 unordered pairs, each eligible to start a process.

### Run processes in parallel

A rule's processes normally advance one after another on the thread that steps the world. With
`onEnter(name, pattern, parallel = true)`, that rule's processes advance at the same time on the
JVM’s common fork-join pool, after the other processes have had their turn. Use it when each
process does real work every frame, such as the physics of [a rope](#ropes-that-go-slack) or a
[shape pinned between points](#shapes-pinned-between-points); for a process that draws one
stroke, sharing out the work costs more than it saves.

A parallel process reads committed facts. Its publications, movements, data changes and
retractions are applied in task order after the serial processes run. A newly published
parallel fact reads `id` 0 until then. Switching a rule between serial and parallel execution
can change the publication IDs assigned across rules. Submitted drawings are collected in
task order.

What the engine cannot order is the box's own variables. Processes that share a counter or a
total may now change it at the same moment, and `standing++` on a plain `var` can then lose a
count. Make it `val standing = 0.atomic` and write `standing += 1`, or `var standing = 0.atomic`
to keep `standing++`; `0.0.atomic` does the same for a total. `"$standing"` prints the value and
`standing.value` reads it. Anything else the processes share should only be read.

## API reference

- Matching and advancing spatial rules: [`SpatialRuleWorld`](reference/data.md#spatial-rules-kt-spatialruleworld), [`SpatialRuleWorld.source`](reference/data.md#spatial-rules-kt--spatialruleworld--source), [`SpatialRuleWorld.rules`](reference/data.md#spatial-rules-kt--spatialruleworld--rules), [`SpatialRuleWorld.step`](reference/data.md#spatial-rules-kt--spatialruleworld--step), [`inspectOnClick`](reference/data.md#spatial-rule-drawing-kt--inspectonclick).

## Where to go next

- [Graphs that change while you watch](dynamic_graph_systems_examples.md): points that connect
  to their neighbours, with edges that strengthen and fade, built directly on a spatial hash.
- [Frames and the animation loop](animation_time_model.md): the clock `play` steps the world on,
  and why animation written against it looks the same live and in a captured video.
- [Querying object graphs](graph_query.md): finding values by pattern in objects you already
  have, rather than in published points.
- The [spatial_rules_tour](agent_transcripts.md#spatial_rules_tour) starting point holds the
  filament, the loom and the torus as a runnable document, and
  [spatial_rules_web](agent_transcripts.md#spatial_rules_web) holds the tutorial's finished web.
