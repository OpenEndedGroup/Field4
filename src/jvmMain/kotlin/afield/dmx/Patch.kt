@file:afield.CompletionCategory("Lighting")

package afield.dmx

import afield.graphics.FLine
import afield.stdlib.Img
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.sampleBilinear
import afield.stdlib.vec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What one DMX slot of a fixture carries. `_FINE` slots are the low byte of the slot before them. */
enum class Slot {
    RED, GREEN, BLUE, WHITE, AMBER,
    /** Intensity. With colour slots present it is the fixture's master [Fixture.dimmer]; alone, it is dimmer × luminance. */
    DIMMER,
    RED_FINE, GREEN_FINE, BLUE_FINE, DIMMER_FINE,
    /** A slot the fixture has but Field leaves at 0 (strobe, macro, mode…). */
    ZERO,
    /** A slot held at 255 (a shutter-open or "lamp on" channel). */
    FULL,
}

/**
 * The channel layout of a kind of fixture: which [Slot] sits at each offset from its start address.
 *
 * The presets cover the common cases — [RGB] pixels and pars, [GRB] (the WS2812 order most pixel
 * controllers expose), [RGBW], [RGBWA], a plain [DIMMER], a 16-bit [DIMMER16], [RGB16] with
 * coarse/fine pairs, and [DRGB] (master dimmer then colour). Anything else is
 * `FixtureProfile("my par", listOf(Slot.DIMMER, Slot.RED, …))`.
 *
 * @property gamma exponent applied to every 0..1 level before quantisation; 1.0 is linear. LED
 *   drivers are linear in PWM, so 2.2 makes a ramp look even to the eye.
 */
class FixtureProfile(val name: String, val slots: List<Slot>, val gamma: Double = 1.0) {
    init {
        require(slots.isNotEmpty() && slots.size <= 512) { "a fixture has 1..512 slots" }
    }

    /** Number of DMX slots the fixture occupies. */
    val footprint: Int get() = slots.size

    /** True if any slot carries colour (so [Slot.DIMMER] is a master, not the whole light). */
    val hasColour: Boolean get() = slots.any { it == Slot.RED || it == Slot.GREEN || it == Slot.BLUE || it == Slot.WHITE || it == Slot.AMBER }

    /** The same layout with a different [gamma]. */
    fun withGamma(gamma: Double) = FixtureProfile(name, slots, gamma)

    override fun toString() = "FixtureProfile($name, $footprint slots)"

    companion object {
        val RGB = FixtureProfile("RGB", listOf(Slot.RED, Slot.GREEN, Slot.BLUE))
        val GRB = FixtureProfile("GRB", listOf(Slot.GREEN, Slot.RED, Slot.BLUE))
        val RGBW = FixtureProfile("RGBW", listOf(Slot.RED, Slot.GREEN, Slot.BLUE, Slot.WHITE))
        val RGBWA = FixtureProfile("RGBWA", listOf(Slot.RED, Slot.GREEN, Slot.BLUE, Slot.WHITE, Slot.AMBER))
        val DIMMER = FixtureProfile("Dimmer", listOf(Slot.DIMMER))
        val DIMMER16 = FixtureProfile("Dimmer16", listOf(Slot.DIMMER, Slot.DIMMER_FINE))
        val RGB16 = FixtureProfile("RGB16", listOf(Slot.RED, Slot.RED_FINE, Slot.GREEN, Slot.GREEN_FINE, Slot.BLUE, Slot.BLUE_FINE))
        val DRGB = FixtureProfile("DRGB", listOf(Slot.DIMMER, Slot.RED, Slot.GREEN, Slot.BLUE))
    }
}

/**
 * One patched fixture: a [profile] at 1-based [address] in [universe], placed at [position] in
 * whatever 2D units the rig is laid out in (the mappers and the visualiser use the same units).
 *
 * [color] is the requested colour, each component 0..1; [dimmer] a 0..1 master. Nothing reaches the
 * wire until the owning [Patch] is rendered into a [DmxOutput].
 */
class Fixture(
    val name: String,
    val profile: FixtureProfile,
    val universe: Int,
    val address: Int,
    var position: Vec2,
) {
    init {
        require(address in 1..512) { "DMX addresses are 1..512, got $address" }
        require(address + profile.footprint - 1 <= 512) {
            "$name: ${profile.footprint} slots from address $address runs past slot 512 of universe $universe"
        }
    }

    /** Requested colour, components 0..1. */
    var color: Vec3 = vec(0.0, 0.0, 0.0)

    /** Master intensity, 0..1. */
    var dimmer: Double = 1.0

    /** Last 1-based address this fixture occupies. */
    val lastAddress: Int get() = address + profile.footprint - 1

    /** Set [color] from components. */
    fun rgb(r: Number, g: Number, b: Number) {
        color = vec(r, g, b)
    }

    /**
     * Write this fixture's slots into the 0-based universe array [data].
     *
     * Colour decomposition, for profiles that have the slot: **white** takes `min(r, g, b)` off all
     * three when [Slot.WHITE] is present; **amber** then takes what it can of the remaining red and
     * green in the proportion 1 : 0.5 — the rough sRGB of a 590–600 nm amber emitter. It is a
     * plausible default, not a calibration: a real rig wants measured primaries.
     */
    fun writeInto(data: ByteArray) {
        var r = color.x.toDouble().coerceIn(0.0, 1.0)
        var g = color.y.toDouble().coerceIn(0.0, 1.0)
        var b = color.z.toDouble().coerceIn(0.0, 1.0)
        val slots = profile.slots
        var w = 0.0
        var a = 0.0
        if (Slot.WHITE in slots) {
            w = min(r, min(g, b)); r -= w; g -= w; b -= w
        }
        if (Slot.AMBER in slots) {
            a = min(r, g / 0.5).coerceIn(0.0, 1.0); r -= a; g -= a * 0.5
        }
        val d = dimmer.coerceIn(0.0, 1.0)
        val colourScale = if (profile.hasColour && Slot.DIMMER in slots) 1.0 else d
        val intensity = if (profile.hasColour) d else d * (0.2126 * r + 0.7152 * g + 0.0722 * b)
        for (i in slots.indices) {
            val at = address - 1 + i
            when (slots[i]) {
                Slot.RED -> put16or8(data, at, r * colourScale, slots.getOrNull(i + 1) == Slot.RED_FINE)
                Slot.GREEN -> put16or8(data, at, g * colourScale, slots.getOrNull(i + 1) == Slot.GREEN_FINE)
                Slot.BLUE -> put16or8(data, at, b * colourScale, slots.getOrNull(i + 1) == Slot.BLUE_FINE)
                Slot.WHITE -> put16or8(data, at, w * colourScale, false)
                Slot.AMBER -> put16or8(data, at, a * colourScale, false)
                Slot.DIMMER -> put16or8(data, at, intensity, slots.getOrNull(i + 1) == Slot.DIMMER_FINE)
                Slot.RED_FINE, Slot.GREEN_FINE, Slot.BLUE_FINE, Slot.DIMMER_FINE -> {} // written with the coarse slot
                Slot.ZERO -> data[at] = 0
                Slot.FULL -> data[at] = 255.toByte()
            }
        }
    }

    private fun put16or8(data: ByteArray, at: Int, level: Double, sixteen: Boolean) {
        val l = if (profile.gamma == 1.0) level else level.pow(profile.gamma)
        if (sixteen) {
            val v = (l * 65535).roundToInt().coerceIn(0, 65535)
            data[at] = (v shr 8).toByte()
            data[at + 1] = v.toByte()
        } else {
            data[at] = (l * 255).roundToInt().coerceIn(0, 255).toByte()
        }
    }

    override fun toString() = "Fixture($name, ${profile.name} @ $universe/$address..$lastAddress)"
}

/**
 * A lighting patch: fixtures, where they sit in DMX space, and where they sit in the room.
 *
 * Fixtures are added one at a time with [add], or in runs with [strip], [grid] and [stripAlong]. The
 * run builders allocate addresses from a cursor that starts at universe [startUniverse], address 1,
 * and **roll over to the next universe whenever the next fixture would straddle slot 512** — so an
 * RGB strip holds 170 pixels per universe (510 slots, 2 left unused) and an RGBW strip 128. That is
 * how pixel controllers expect long strips to be laid out.
 *
 * Colours are set by the mappers — [map] (a function of position and time), [mapAlong] (of distance
 * along the fixture list), [mapImage] (sampled from an [Img]) — or directly on [Fixture.color].
 * [render] writes every fixture into a [DmxOutput]; [toFLines] draws the rig in its current colours.
 */
class Patch(val startUniverse: Int = 1) {
    /** Every fixture, in the order it was added. */
    val fixtures = mutableListOf<Fixture>()

    private var cursorUniverse = startUniverse
    private var cursorAddress = 1
    private val startNanos = System.nanoTime()

    /** Seconds since this patch was made; the default `t` for [map] and [mapAlong]. */
    val time: Double get() = (System.nanoTime() - startNanos) / 1e9

    /** Universes any fixture touches, ascending. */
    val universes: List<Int> get() = fixtures.map { it.universe }.distinct().sorted()

    /** Where the next allocated fixture will go, as `universe to address`. */
    val cursor: Pair<Int, Int> get() = cursorUniverse to cursorAddress

    /** Move the allocation cursor, e.g. to start the next run on a fresh universe. */
    fun moveCursor(universe: Int, address: Int = 1) {
        require(address in 1..512)
        cursorUniverse = universe; cursorAddress = address
    }

    /** Patch one fixture at an explicit address. Does not move the cursor. */
    fun add(profile: FixtureProfile, universe: Int, address: Int, position: Vec2, name: String = "${profile.name} ${fixtures.size + 1}"): Fixture =
        Fixture(name, profile, universe, address, position).also { fixtures += it }

    /** Patch one fixture at the cursor, rolling to the next universe if it would not fit. */
    fun next(profile: FixtureProfile, position: Vec2, name: String = "${profile.name} ${fixtures.size + 1}"): Fixture {
        if (cursorAddress + profile.footprint - 1 > 512) {
            cursorUniverse++; cursorAddress = 1
        }
        val f = add(profile, cursorUniverse, cursorAddress, position, name)
        cursorAddress += profile.footprint
        if (cursorAddress > 512) { cursorUniverse++; cursorAddress = 1 }
        return f
    }

    /**
     * A straight strip of [count] fixtures from [from] to [to] (inclusive), allocated from the cursor.
     */
    fun strip(count: Int, from: Vec2, to: Vec2, profile: FixtureProfile = FixtureProfile.RGB, name: String = "pixel"): List<Fixture> {
        require(count >= 1)
        return (0 until count).map { i ->
            val u = if (count == 1) 0.0 else i / (count - 1.0)
            next(profile, vec(from.x + (to.x - from.x) * u, from.y + (to.y - from.y) * u), "$name $i")
        }
    }

    /**
     * A strip of [count] fixtures laid evenly by arc length along [line] — an LED tape stuck to a
     * drawn curve. Positions come from `FLine.sampleByNumber`, so the ends of the line are the ends
     * of the strip.
     */
    fun stripAlong(line: FLine, count: Int, profile: FixtureProfile = FixtureProfile.RGB, name: String = "pixel"): List<Fixture> {
        require(count >= 2) { "a strip along a line needs at least 2 pixels" }
        return line.sampleByNumber(count).mapIndexed { i, p -> next(profile, vec(p.x, p.y), "$name $i") }
    }

    /**
     * A [cols] × [rows] matrix with [spacing] between fixtures, top-left at [origin], allocated in
     * rows (or in a serpentine, as most LED matrices are wired, when [serpentine] is true).
     */
    fun grid(cols: Int, rows: Int, spacing: Double = 10.0, origin: Vec2 = vec(0.0, 0.0),
             profile: FixtureProfile = FixtureProfile.RGB, serpentine: Boolean = false, name: String = "pixel"): List<Fixture> {
        require(cols >= 1 && rows >= 1)
        val out = ArrayList<Fixture>(cols * rows)
        for (r in 0 until rows) for (k in 0 until cols) {
            val c = if (serpentine && r % 2 == 1) cols - 1 - k else k
            out += next(profile, vec(origin.x + c * spacing, origin.y + r * spacing), "$name $c,$r")
        }
        return out
    }

    /** Colour every fixture (or [only] these) from its position and time: `fn(x, y, t)` returns rgb 0..1. */
    fun map(t: Double = time, only: List<Fixture> = fixtures, fn: (x: Double, y: Double, t: Double) -> Vec3): Patch {
        for (f in only) f.color = fn(f.position.x.toDouble(), f.position.y.toDouble(), t)
        return this
    }

    /**
     * Colour [only] (default every fixture) by position along the list: `fn(u, t)` with `u` running
     * 0..1 from the first fixture to the last — a gradient or a chase down a strip.
     */
    fun mapAlong(t: Double = time, only: List<Fixture> = fixtures, fn: (u: Double, t: Double) -> Vec3): Patch {
        val n = only.size
        only.forEachIndexed { i, f -> f.color = fn(if (n <= 1) 0.0 else i / (n - 1.0), t) }
        return this
    }

    /**
     * Colour fixtures by sampling [img] bilinearly. The rig's bounding box (or [area], as
     * `x, y, w, h` in rig units) is stretched over the whole image, so the fixtures act as a
     * low-resolution screen. Gray images drive r = g = b.
     */
    fun mapImage(img: Img, only: List<Fixture> = fixtures, area: DoubleArray? = null): Patch {
        if (only.isEmpty()) return this
        val (x0, y0, w, h) = area?.toList() ?: bounds(only)
        for (f in only) {
            val u = if (w <= 0) 0.5 else (f.position.x - x0) / w
            val v = if (h <= 0) 0.5 else (f.position.y - y0) / h
            val px = u * (img.width - 1)
            val py = v * (img.height - 1)
            f.color = if (img.channels == 1) {
                val g = img.sampleBilinear(px, py, 0)
                vec(g, g, g)
            } else vec(img.sampleBilinear(px, py, 0), img.sampleBilinear(px, py, 1), img.sampleBilinear(px, py, 2))
        }
        return this
    }

    /** `[x, y, w, h]` of the fixtures' positions. */
    fun bounds(only: List<Fixture> = fixtures): List<Double> {
        if (only.isEmpty()) return listOf(0.0, 0.0, 0.0, 0.0)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (f in only) {
            x0 = min(x0, f.position.x.toDouble()); y0 = min(y0, f.position.y.toDouble())
            x1 = max(x1, f.position.x.toDouble()); y1 = max(y1, f.position.y.toDouble())
        }
        return listOf(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * Write every fixture into [universeData] — a function from universe number to its 512-byte
     * array — without any networking. [render] into an output is this plus the frame lock.
     */
    fun renderInto(universeData: (Int) -> ByteArray) {
        for (f in fixtures) f.writeInto(universeData(f.universe))
    }

    /** Write every fixture into [out]'s universes, atomically with respect to its sender thread. */
    fun render(out: DmxOutput) = out.frame { renderInto { universe(it).data } }

    /**
     * Animate this patch on [out]'s sender clock: before every tick, [fn] runs with seconds since the
     * output started (typically calling [map] / [mapAlong] / [mapImage]), then the patch is rendered
     * into [out]. Replaces any previous animation on that output, so re-running a box is safe; stops
     * when the output closes (i.e. when the owning box is stopped).
     */
    fun animate(out: DmxOutput, fn: Patch.(t: Double) -> Unit): Patch {
        out.animate { t ->
            fn(t)
            render(out)
        }
        return this
    }

    /** Set every fixture to black. */
    fun blackout(): Patch {
        fixtures.forEach { it.color = vec(0.0, 0.0, 0.0) }
        return this
    }

    override fun toString(): String {
        val byProfile = fixtures.groupingBy { it.profile.name }.eachCount()
        return "Patch(${fixtures.size} fixtures $byProfile, universes $universes)"
    }
}
