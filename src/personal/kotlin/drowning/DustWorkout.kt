package drowning

import afield.Box
import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderReport
import afield.stdlib.shaderTemplate
import afield.stdlib.stackAutoShaderSources
import kotlin.math.abs
import kotlin.math.sqrt

/*
FIELD_TEST
id: drowning.dust.workout
runner: jvm-main
entrypoint: drowning.DustWorkout
tags: [smoke, drowning, dust, shaders, stacksl, particles]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "dust workout passed"
*/

/**
 * The `dust` package, checked headlessly — no window, no device, no GPU.
 *
 * Three kinds of assertion, and the split matters because only the first kind is a *property*:
 *
 * 1. **The network is what it claims to be.** The orthant search is checked against a brute-force
 *    search over the same cloud, so this says something even when the grid, the ring bound or the
 *    parallelism changes. Determinism is checked by running it serially and comparing.
 * 2. **The two sides of the wire agree.** [DustLayout]'s byte counts against what
 *    [DustField.writeState] actually writes; the shaders' `DUST_*` constants against the Kotlin ones;
 *    and every push-constant name the boxes bind against what the shaders declare. That last one is
 *    the failure this catches for real — a shader one revision behind gives a box that throws
 *    `can't find <name> in a mesh in this module` the moment it runs, while looking fine on the page.
 * 3. **Everything compiles.** Every shader in [DustShaders.ALL], in *both* stereo modes, with the
 *    `stack.auto` slots expanded; and every StackSL program in [DustPrograms.ALL], checked for the one
 *    thing a program here must not do, which is need a texture sampler.
 *
 * What it cannot check is whether any of it *looks* like anything, or whether MoltenVK's multiview
 * actually renders both layers. Those need the document and a screen.
 *
 * ```bash
 * python3 tools/run_field_tests.py --refresh-catalog --id drowning.dust.workout
 * ```
 */
object DustWorkout {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "dust workout failed" }
    }

    fun runTests(): Boolean {
        layoutArithmeticMatchesWhatIsWritten()
        shaderConstantsMatchKotlin()
        orthantSearchMatchesBruteForce()
        orthantSearchIsDeterministic()
        orthantOctantsAreCorrectlyAssigned()
        trimmedNetworkIsWellFormed()
        mutualOnlyIsExactlySymmetric()
        ringCapIsTheHullAndNotTheBulk()
        historyCursorAgreesWithTheHeaderReader()
        historyBufferIsBigEnoughForWhatTheShaderIndexes()
        everyStackSLProgramCompilesWithoutASampler()
        everyProgramLeavesACleanStack()
        everyProgramShippedInABoxIsChecked()
        everyShaderCompilesInBothStereoModes()
        stereoModesDifferOnlyInTheEyeIndex()
        pushConstantsTheBoxesBindAreDeclared()
        forceShaderReadsPrevStateAndWritesState()
        theEdgeForceSignIsRestoring()
        theBounceHasNoRestingFixedPoint()
        theBillboardIsBuiltInWorldSpace()
        theDefaultNoiseIsThreeDimensional()
        everyLaneNameIsUniqueAndDeclared()
        laneAssignmentsMatchTheFactories()
        println("dust workout passed")
        return true
    }

    // ---- 2. the two sides of the wire ----------------------------------------------------------

    private fun layoutArithmeticMatchesWhatIsWritten() {
        val field = DustField.random(7, extent = 1f)
        val links = DustOrthant.build(field)

        val state = java.nio.ByteBuffer.allocate(field.count * DustLayout.PARTICLE_BYTES)
        field.writeState(state)
        check(state.position() == field.count * DustLayout.PARTICLE_BYTES) {
            "writeState wrote ${state.position()} bytes, PARTICLE_BYTES says ${field.count * DustLayout.PARTICLE_BYTES}"
        }

        val linkBytes = java.nio.ByteBuffer.allocate(field.count * DustLayout.LINKS_BYTES)
        field.writeLinks(linkBytes, links)
        check(linkBytes.position() == field.count * DustLayout.LINKS_BYTES) {
            "writeLinks wrote ${linkBytes.position()} bytes, LINKS_BYTES says ${field.count * DustLayout.LINKS_BYTES}"
        }

        // std430 alignment: both records are all-vec4/uvec4, so both strides must be multiples of 16 or
        // the shader's `p[i]` indexing walks off the record it is reading.
        check(DustLayout.PARTICLE_BYTES % 16 == 0) { "PARTICLE_BYTES ${DustLayout.PARTICLE_BYTES} is not 16-aligned" }
        check(DustLayout.LINKS_BYTES % 16 == 0) { "LINKS_BYTES ${DustLayout.LINKS_BYTES} is not 16-aligned" }
        check(DustLayout.HISTORY_HEADER_BYTES % 16 == 0) { "the history header must be vec4-aligned" }

        check(DustLayout.stateVec4s(field.count) * 16 == field.count * DustLayout.PARTICLE_BYTES) {
            "stateVec4s disagrees with PARTICLE_BYTES"
        }
        check(DustLayout.linksVec4s(field.count) * 16 == field.count * DustLayout.LINKS_BYTES) {
            "linksVec4s disagrees with LINKS_BYTES"
        }

        // Round-trip one particle's position out of the bytes, so the field order in `writeState` is
        // pinned rather than merely the length. Position first is what every draw assumes.
        val readBack = state.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until field.count) {
            val at = i * DustLayout.PARTICLE_BYTES
            for (a in 0 until 3) check(readBack.getFloat(at + a * 4) == field.position[i * 3 + a]) {
                "particle $i axis $a: state buffer has ${readBack.getFloat(at + a * 4)}, field has ${field.position[i * 3 + a]}"
            }
            check(readBack.getFloat(at + 12) == field.invMass[i]) { "pos.w must be 1/mass" }
            // prev == pos at rest, so the first Verlet step reads zero velocity.
            for (a in 0 until 3) check(readBack.getFloat(at + 16 + a * 4) == field.position[i * 3 + a]) {
                "prev must start equal to pos, or the field jumps on frame one"
            }
            check(readBack.getFloat(at + 28) == field.hash[i]) { "prev.w must be the per-particle hash" }
        }
    }

    private fun shaderConstantsMatchKotlin() {
        val defines = DustShaders.DUST_DEFINES
        check("#define DUST_EDGES ${DustLayout.EDGES}" in defines) {
            "the shader's DUST_EDGES does not match DustLayout.EDGES = ${DustLayout.EDGES}:\n$defines"
        }
        check("#define DUST_NONE  0x%08Xu".format(DustLayout.NONE) in defines) {
            "the shader's DUST_NONE does not match DustLayout.NONE"
        }
        check("#define DUST_HISTORY_MAGIC   0x%08Xu".format(DustLayout.HISTORY_MAGIC) in defines) {
            "the shader's DUST_HISTORY_MAGIC does not match DustLayout.HISTORY_MAGIC"
        }
        check("#define DUST_HISTORY_VERSION ${DustLayout.HISTORY_VERSION}u" in defines) {
            "the shader's DUST_HISTORY_VERSION does not match DustLayout.HISTORY_VERSION"
        }

        // The two records' member order, spelled out in the struct, is what the writers above assume.
        val refs = DustShaders.DUST_REFS
        check(Regex("""struct DustParticle \{\s*vec4 pos;.*?vec4 prev;.*?vec4 aux;""", RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn(refs)) { "DustParticle's member order changed; DustField.writeState did not" }
        check(Regex("""struct DustLinks \{\s*vec4  rest;.*?uvec4 nbrA;.*?uvec4 nbrB;.*?vec4  lenA;.*?vec4  lenB;""",
            RegexOption.DOT_MATCHES_ALL).containsMatchIn(refs)) {
            "DustLinks's member order changed; DustField.writeLinks did not"
        }
    }

    // ---- 1. the network is what it claims to be ------------------------------------------------

    /**
     * The grid search against a brute-force search over the same cloud.
     *
     * This is the assertion that makes the rest of the package trustworthy: the grid, the CSR build, the
     * ring shells and the termination bound are all opportunities to miss a neighbour, and a missed
     * neighbour is invisible in a rendered field.
     */
    private fun orthantSearchMatchesBruteForce() {
        for (count in intArrayOf(2, 5, 64, 900)) {
            val field = DustField.random(count, extent = 1.3f, seed = 991L + count)
            // A cap high enough that the grid can always reach the far corner, so any disagreement is
            // the search being wrong rather than the cap being hit.
            val found = DustOrthant.directedEdges(field, DustOrthantSettings(maxRings = 1000))
            val expected = bruteForceOrthants(field)

            for (i in 0 until count) for (o in 0 until 8) {
                val a = found.neighbour[i * 8 + o]
                val b = expected[i * 8 + o]
                if (a == b) continue
                // A genuine distance tie between two candidates in the same octant is allowed to resolve
                // either way; anything else is a miss.
                check(a != DustLayout.NONE && b != DustLayout.NONE &&
                    abs(field.distance(i, a) - field.distance(i, b)) < 1e-6f) {
                    "n=$count particle $i octant $o: grid found $a" +
                        (if (a != DustLayout.NONE) " at %.6f".format(field.distance(i, a)) else "") +
                        ", brute force found $b" +
                        (if (b != DustLayout.NONE) " at %.6f".format(field.distance(i, b)) else "")
                }
            }
        }
    }

    /** Every octant, by exhaustive search. The reference the grid is measured against. */
    private fun bruteForceOrthants(field: DustField): IntArray {
        val n = field.count
        val out = IntArray(n * 8) { DustLayout.NONE }
        val best = FloatArray(n * 8) { Float.MAX_VALUE }
        for (i in 0 until n) for (j in 0 until n) {
            if (i == j) continue
            val dx = field.position[j * 3] - field.position[i * 3]
            val dy = field.position[j * 3 + 1] - field.position[i * 3 + 1]
            val dz = field.position[j * 3 + 2] - field.position[i * 3 + 2]
            val d2 = dx * dx + dy * dy + dz * dz
            val o = DustOrthant.octant(dx, dy, dz)
            if (d2 < best[i * 8 + o]) { best[i * 8 + o] = d2; out[i * 8 + o] = j }
        }
        return out
    }

    private fun orthantSearchIsDeterministic() {
        val field = DustField.random(4000, extent = 1f, seed = 7L)
        val parallel = DustOrthant.build(field, DustOrthantSettings(parallel = true))
        val serial = DustOrthant.build(field, DustOrthantSettings(parallel = false))
        check(parallel.neighbour.contentEquals(serial.neighbour)) {
            "the parallel and serial searches disagree on the neighbours — a network you cannot reproduce"
        }
        check(parallel.restLength.contentEquals(serial.restLength)) {
            "the parallel and serial searches disagree on the rest lengths"
        }
        // Twice on the same thread, too: a stale accumulator inside the search would show up here and
        // not in the parallel comparison.
        val again = DustOrthant.build(field, DustOrthantSettings(parallel = true))
        check(parallel.neighbour.contentEquals(again.neighbour)) { "two identical runs disagree" }
    }

    private fun orthantOctantsAreCorrectlyAssigned() {
        // The sign convention, stated directly rather than read off the implementation.
        check(DustOrthant.octant(1f, 1f, 1f) == 0) { "+++ must be octant 0" }
        check(DustOrthant.octant(-1f, 1f, 1f) == 1) { "-++ must set bit 0" }
        check(DustOrthant.octant(1f, -1f, 1f) == 2) { "+-+ must set bit 1" }
        check(DustOrthant.octant(1f, 1f, -1f) == 4) { "++- must set bit 2" }
        check(DustOrthant.octant(-1f, -1f, -1f) == 7) { "--- must be octant 7" }
        check(DustOrthant.octant(0f, 0f, 0f) == 0) { "zero counts as positive" }

        // And the property that makes it a *neighbourhood*: every found neighbour really is in the
        // octant it was filed under.
        val field = DustField.random(1500, extent = 1f, seed = 3L)
        val net = DustOrthant.directedEdges(field)
        for (i in 0 until field.count) for (o in 0 until 8) {
            val j = net.neighbour[i * 8 + o]
            if (j == DustLayout.NONE) continue
            val actual = DustOrthant.octant(
                field.position[j * 3] - field.position[i * 3],
                field.position[j * 3 + 1] - field.position[i * 3 + 1],
                field.position[j * 3 + 2] - field.position[i * 3 + 2]
            )
            check(actual == o) { "particle $i filed $j under octant $o, but it is in $actual" }
            check(abs(net.distance[i * 8 + o] - field.distance(i, j)) < 1e-4f) {
                "recorded distance ${net.distance[i * 8 + o]} != actual ${field.distance(i, j)}"
            }
        }

        // A cloud that is really a plane has four empty octants. Worth pinning: it is the case where a
        // directional network degenerates, and the occupancy is how you would notice.
        val flat = DustField(
            300,
            FloatArray(900) { if (it % 3 == 2) 0f else (it * 0.3719f % 1f) - 0.5f },
            FloatArray(300) { 1f }, FloatArray(300) { 0.5f }, FloatArray(300) { 1f }
        )
        val flatNet = DustOrthant.directedEdges(flat)
        val occupancy = flatNet.octantOccupancy()
        // z is exactly 0 everywhere, so dz == 0 files as positive: only octants 0..3 can be occupied.
        check(occupancy.drop(4).all { it == 0 }) {
            "a planar cloud should leave the negative-z octants empty, got ${occupancy.toList()}"
        }
        check(occupancy.take(4).all { it > 0 }) { "a planar cloud should fill the positive-z octants" }
    }

    private fun trimmedNetworkIsWellFormed() {
        val field = DustField.random(5000, extent = 1f, seed = 11L)
        val links = DustOrthant.build(field)
        val slots = DustLayout.EDGES

        check(links.count == field.count) { "links describe the wrong number of particles" }
        check(links.neighbour.size == field.count * slots) { "the neighbour array is the wrong size" }
        check(links.isolated() == 0) { "${links.isolated()} particles have no edges at all" }

        for (i in 0 until field.count) {
            val row = (0 until slots).map { links.neighbour[i * slots + it] }
            val filled = row.filter { it != DustLayout.NONE }

            check(filled.size == links.degree[i]) {
                "particle $i: degree says ${links.degree[i]} but $${filled.size} slots are filled"
            }
            // Absent slots must be at the end: the shaders `continue` on DUST_NONE rather than break,
            // so a hole would not be a bug today — but the edge draw's vertex arithmetic assumes a
            // dense prefix and this is cheaper to assert than to remember.
            check(row.take(filled.size) == filled) { "particle $i has a hole in its slots: $row" }
            check(i !in filled) { "particle $i is its own neighbour" }
            check(filled.distinct().size == filled.size) { "particle $i lists a neighbour twice: $filled" }

            for (s in filled.indices) {
                val j = links.neighbour[i * slots + s]
                val recorded = links.restLength[i * slots + s]
                check(recorded > 0f) { "particle $i slot $s has a non-positive rest length $recorded" }
                check(abs(recorded - field.distance(i, j)) < 1e-4f) {
                    "particle $i slot $s: rest length $recorded != distance ${field.distance(i, j)}"
                }
            }
            // Shortest-first: the trim keeps the short edges, which is what carries local structure.
            val lengths = filled.indices.map { links.restLength[i * slots + it] }
            check(lengths == lengths.sorted()) { "particle $i's slots are not shortest-first: $lengths" }

            check(abs(links.meanLength(i) - lengths.average().toFloat()) < 1e-4f) {
                "particle $i's recorded mean length disagrees with its own slots"
            }
        }

        // The union cannot lose edges the directed search found, and the trim cannot add any.
        check(links.unionEdges * 2 >= links.directedEdges) {
            "the union has ${links.unionEdges} undirected edges, fewer than half the ${links.directedEdges} directed ones"
        }
        check(links.edgeCount <= links.unionEdges) { "the trim added edges" }

        // The rest-length spread is the reason for using a uniform random cloud; if it ever collapses
        // the field will look like a lattice and this is where to find out.
        val spread = links.restLength.filterIndexed { k, _ -> links.neighbour[k] != DustLayout.NONE }
        val mean = spread.average()
        val sd = sqrt(spread.sumOf { (it - mean) * (it - mean) } / spread.size)
        check(sd / mean > 0.1) { "rest lengths are suspiciously uniform: sd/mean = ${sd / mean}" }
    }

    private fun mutualOnlyIsExactlySymmetric() {
        val field = DustField.random(3000, extent = 1f, seed = 17L)
        val mutual = DustOrthant.build(field, DustOrthantSettings(mutualOnly = true))
        check(mutual.asymmetricSlots() == 0) {
            "mutualOnly left ${mutual.asymmetricSlots()} one-sided slots"
        }
        val loose = DustOrthant.build(field, DustOrthantSettings(mutualOnly = false))
        check(loose.slotCount >= mutual.slotCount) { "mutualOnly kept more slots than the union did" }
        // The default is the looser one because it keeps more of the network; how much more is the
        // number that justifies the choice, so print it rather than only asserting on it.
        println(
            "  onn symmetry: own-first keeps ${loose.slotCount} slots " +
                "(${loose.asymmetricSlots()} one-sided), mutual-only keeps ${mutual.slotCount}"
        )
    }

    private fun ringCapIsTheHullAndNotTheBulk() {
        val field = DustField.random(20000, extent = 1f, seed = 23L)
        val links = DustOrthant.build(field)
        // Particles that stop on the ring cap are the ones with a genuinely empty octant, which on a
        // cube is the hull. A cube of 20k has roughly 20000^(2/3) ~ 700 hull particles; if this is a
        // large fraction of the cloud the grid is too fine and every search is walking empty rings.
        val fraction = links.ringLimited.toDouble() / field.count
        check(fraction < 0.25) {
            "${links.ringLimited} of ${field.count} searches hit the ring cap (${"%.1f".format(fraction * 100)}%) " +
                "— the grid is too fine for the cloud"
        }
        println("  onn ring cap: ${links.ringLimited}/${field.count} searches, cell ${"%.4f".format(links.cell)}")
        println("  " + links.describe().replace("\n", "\n  "))
    }

    // ---- the history circular buffer -----------------------------------------------------------

    private fun historyCursorAgreesWithTheHeaderReader() {
        val capacity = 5
        val particles = 3
        // Start the frame counter well past zero, the way the renderer's is by the time the history box
        // runs, and rebase — which is the bug this shape exists to prevent: counting from renderer frame
        // 0 made `written` saturate immediately and the trail draw believed a ring of samples that had
        // never been written.
        var frame = 977L
        val cursor = DustHistoryCursor(capacity) { frame }
        cursor.rebase()
        check(cursor.written == 1) { "immediately after rebase exactly one frame is written, not ${cursor.written}" }
        check(cursor.slot == 0) { "immediately after rebase the slot is 0, not ${cursor.slot}" }
        val base = frame

        // Walk twice round the ring. The interesting frames are the ones before it fills, where `count`
        // is below `capacity` and the trail draw has to clip the samples that do not exist yet.
        while (frame < base + capacity * 2L + 2) {
            val header = cursor.expectedHeader(particles)
            check(header.valid) { "the cursor produced an invalid header at frame $frame: ${header.check()}" }
            check(header.head == cursor.head) { "header head disagrees with the cursor" }
            check(cursor.slot == ((frame - base) % capacity).toInt()) {
                "the slot must be the frames-since-rebase, mod capacity"
            }

            // Age 0 is the slot just written; every age below `count` must map to a distinct slot.
            val slots = (0 until header.count).map { header.slotForAge(it) }
            check(slots.distinct().size == slots.size) {
                "frame $frame: ages 0..${header.count - 1} collide on slots $slots"
            }
            check(slots.all { it in 0 until capacity }) { "frame $frame: a slot escaped the ring: $slots" }
            check(header.slotForAge(0) == cursor.slot) {
                "age 0 must be the slot the shader just wrote (${cursor.slot}), got ${header.slotForAge(0)}"
            }
            check(header.slotForAge(header.count) == -1) { "an age past `count` must not resolve" }
            check(header.slotForAge(-1) == -1) { "a negative age must not resolve" }
            frame++
        }
        check(cursor.written == capacity) { "`written` must saturate at capacity" }

        // Back at the origin: one sample, so a trail has no segment to draw yet and the shader clips.
        frame = base
        check(cursor.written == 1 && cursor.slot == 0) { "at the origin: slot 0, one sample written" }
        // And a rebase mid-run empties it again — which is what re-running the history box should do.
        frame = base + 500
        cursor.rebase()
        check(cursor.written == 1 && cursor.slot == 0) { "rebase must empty the ring" }

        // The derivation is the load-bearing part — it is what removes the ordering hazard — so check it
        // as arithmetic too, including the wrap and a frame counter that has run for a while.
        check(DustHistoryCursor.slotFor(0, 64) == 0)
        check(DustHistoryCursor.slotFor(63, 64) == 63)
        check(DustHistoryCursor.slotFor(64, 64) == 0)
        check(DustHistoryCursor.slotFor(1_000_003, 64) == (1_000_003 % 64))
        check(DustHistoryCursor.writtenFor(0, 64) == 1)
        check(DustHistoryCursor.writtenFor(62, 64) == 63)
        check(DustHistoryCursor.writtenFor(1_000_003, 64) == 64)

        // The all-zero header — an allocated buffer the shader has never touched — must be caught, not
        // read as an empty-but-valid history.
        val zero = DustHistoryHeader.read(java.nio.ByteBuffer.allocate(DustLayout.HISTORY_HEADER_BYTES))
        check(!zero.valid) { "an untouched buffer must not read as a valid header" }
        check(zero.check()!!.contains("never run")) { "the message for an untouched buffer should say so" }
    }

    /**
     * The largest index the history shader and the trail draw can produce, against the allocation.
     *
     * Both compute `slot * count + particle`, so the top index is `(capacity-1) * count + count-1` and
     * the buffer needs one header plus exactly that many samples. Off by one here is a GPU write past
     * the end of a buffer, which is the class of bug that shows up as something unrelated being corrupt.
     */
    private fun historyBufferIsBigEnoughForWhatTheShaderIndexes() {
        for ((count, capacity) in listOf(1 to 1, 3 to 5, 40000 to 64, 100000 to 96)) {
            val vec4s = DustLayout.historyVec4s(count, capacity)
            val headerVec4s = DustLayout.HISTORY_HEADER_BYTES / 16
            val topIndex = (capacity - 1).toLong() * count + (count - 1)
            check(headerVec4s + topIndex + 1 == vec4s.toLong()) {
                "count=$count capacity=$capacity: the shader's top index is $topIndex, " +
                    "but historyVec4s allocates ${vec4s - headerVec4s} samples"
            }
        }
        // And the reported total, which is what a box prints before allocating anything.
        val bytes = DustLayout.deviceBytes(40000, 64)
        val expected = 2L * 40000 * DustLayout.PARTICLE_BYTES + 40000L * DustLayout.LINKS_BYTES +
            DustLayout.historyVec4s(40000, 64).toLong() * 16
        check(bytes == expected) { "deviceBytes disagrees with its own parts" }
    }

    // ---- 3. everything compiles ----------------------------------------------------------------

    /**
     * Every program in [DustPrograms], compiled the way `stack.auto` compiles it.
     *
     * The options here are not arbitrary: they are exactly the ones `compileStackAutoShaderFunction`
     * uses (`includeHeader = false`, `declareSamplers = false`, `tolerant = false`), so a program that
     * passes here passes in the box. `tolerant = false` matters most — a tolerant compile turns an
     * unknown word into a noop and a typo would sail through as silence.
     */
    private fun everyStackSLProgramCompilesWithoutASampler() {
        for ((name, program) in DustPrograms.ALL) {
            val result = StackSLCompiler.compile(
                program,
                StackSLCompileOptions(
                    prefix = name.substringBefore('/'),
                    includeHeader = false,
                    declareSamplers = false,
                    tolerant = false,
                    externalizeConstants = false,
                    sampleTextures = listOf("source")
                )
            )
            check(result.ok) {
                "the force program '$name' does not compile:\n" +
                    result.diagnostics.filter { it.severity == StackSLSeverity.ERROR }.joinToString("\n") +
                    "\n--- program ---\n$program"
            }
            // The compute shader declares no sampler at all, so a program that reaches for one produces
            // GLSL referring to an undeclared `source` and fails much later with no hint of the StackSL.
            check("source" !in result.glsl) {
                "the force program '$name' needs a texture sampler; the force shader has none:\n$program"
            }
            check("vec4 ${name.substringBefore('/')}(" in result.glsl) {
                "'$name' did not compile to an entry point:\n${result.glsl}"
            }
        }

        // The two curated sets must name real slots — a program bound into the wrong slot compiles fine
        // and then behaves strangely, which is the mistake worth making impossible.
        val slots = setOf("edgeForce", "noiseForce", "restForce", "velocityShape")
        for (set in listOf(DustPrograms.DEFAULTS, DustPrograms.WILD)) {
            check(set.keys == slots) { "a curated program set names ${set.keys}, not $slots" }
        }
        for (slot in slots) check(DustPrograms.forSlot(slot).isNotEmpty()) { "no programs offered for $slot" }
        println("  stacksl: ${DustPrograms.ALL.size} force programs compile, none needs a sampler")
    }

    /**
     * Every function of every shipped program must end with **exactly one value on the stack**.
     *
     * This is the mistake a stack language invites, and StackSL does not report it. A definition has to
     * consume its input and leave its result — net zero. Opening one with `=name` (which *copies*,
     * `a -- a`) instead of `>name` (which *moves*, `a --`) leaves the input sitting under the result, so
     * every call pushes two values, and everything downstream silently takes the wrong operand.
     *
     * That happened, in the default noise program. Three `fbm3` calls feeding a `vec3` came out as: the
     * first call's result pruned away entirely, and the force's `y` channel reading a raw *coordinate*
     * off the stack instead of a noise sample — a constant of about `+6.8` on one axis. The program
     * compiled, the shader compiled, `result.ok` was true, and there were **zero diagnostics**. It was
     * found by a person noticing that turning the gain up translated the field instead of agitating it.
     *
     * What StackSL does expose is `analysis.functions[].finalStackDepth` — `2` for that definition and
     * `6` for its root, against `1` for both — plus `prunedValues`, which counts the work thrown away.
     * Free to check, and it turns a silent wrong answer into a named failure.
     *
     * The rule itself now lives in core, as `afield.stdlib.stackAutoShaderReport` — `stack.auto` reports
     * it into a box's output channel on every bind, so it is in front of whoever is live-coding rather
     * than only in a test. This is the thin consumer: assert the report is clean for everything shipped.
     *
     * `notes` (chiefly pruned values) is printed rather than asserted: pruning is legitimate — a marked
     * value that goes unused is pruned by design — so a count is a smell to show, not a rule to enforce.
     */
    private fun everyProgramLeavesACleanStack() {
        for ((name, program) in DustPrograms.ALL) {
            val slot = name.substringBefore('/')
            val report = stackAutoShaderReport(program, slot)
            check(report.compiled) { "'$name' does not compile" }
            check(report.problems.isEmpty()) { "'$name': ${report.problems.joinToString("; ")}" }
            check(report.warnings.isEmpty()) {
                "'$name' compiles with warnings, which is how two bugs here got through: " +
                    report.warnings.joinToString("; ")
            }
            if (report.notes.isNotEmpty()) println("  note: '$name' ${report.notes.joinToString("; ")}")
        }
        println("  stack: ${DustPrograms.ALL.size} programs report clean " +
            "(no unbalanced definitions, no warnings)")
    }

    /**
     * The StackSL programs written **inline in the box sources** get the same gauntlet as
     * [DustPrograms].
     *
     * `dust_two` moved the default programs out of `DustPrograms` and into the box as literal text, so
     * that changing one is a Cmd-Enter rather than a Gradle build and a restart. That is the right
     * trade and it opened a hole: the box is a *resource*, not compiled Kotlin, so nothing looked at
     * those strings at all. The fix for the noise program's stack-arity bug went into
     * `DustPrograms.DRIFT` and the box kept shipping the broken copy — caught by reading the document
     * back, not by any check.
     *
     * So: pull every `<module>.<slot> = "…"` out of the box sources and run them through
     * [everyStackSLProgramCompilesWithoutASampler] and [everyProgramLeavesACleanStack]'s rules. The box
     * text is what actually runs, so it is the text that has to be right.
     */
    private fun everyProgramShippedInABoxIsChecked() {
        val slots = setOf("edgeForce", "noiseForce", "restForce", "velocityShape")

        // The box sources are packaged as resources, so read them that way and fall back to the tree —
        // the jvm-main runner's working directory is the repo root, but the resource is the real
        // artefact and it is what `build_starting_point.py` embeds.
        fun boxSource(name: String): String? =
            DustWorkout::class.java.getResourceAsStream("/dust/$name")?.bufferedReader()?.readText()
                ?: java.io.File("src/personal/resources/dust/$name").takeIf { it.exists() }?.readText()

        val boxes = listOf("box_forces.kt")
        var found = 0
        for (box in boxes) {
            val text = checkNotNull(boxSource(box)) { "cannot read the box source $box as a resource or a file" }

            // `module.slot = """…"""` and `module.slot = "…"`, in that order so the triple form wins.
            val triple = Regex("""(\w+)\.(\w+)\s*=\s*"{3}(.*?)"{3}""", RegexOption.DOT_MATCHES_ALL)
            val single = Regex("""(\w+)\.(\w+)\s*=\s*"((?:[^"\\\n]|\\.)*)"""")

            val programs = mutableMapOf<String, String>()
            for (m in triple.findAll(text)) {
                if (m.groupValues[2] in slots) programs[m.groupValues[2]] = m.groupValues[3]
            }
            for (m in single.findAll(text)) {
                val slot = m.groupValues[2]
                if (slot in slots) programs.putIfAbsent(slot, m.groupValues[3])
            }

            check(programs.keys == slots) {
                "$box should bind all of $slots inline; it binds ${programs.keys}"
            }

            for ((slot, program) in programs) {
                found++
                // The same report `stack.auto` publishes into the box's output channel when this binds.
                val report = stackAutoShaderReport(program, slot)
                check(report.quiet) {
                    "$box binds a `$slot` program that `stack.auto` would report on:\n" +
                        report.lines.joinToString("\n") + "\n--- program ---\n$program"
                }
                // The sampler rule is this shader's, not StackSL's: the force compute declares none.
                val glsl = afield.stacksl.compileStackSLToGlsl(
                    source = program, prefix = slot, declareSamplers = false
                )
                check("source" !in glsl) {
                    "$box's `$slot` program needs a texture sampler; the force shader has none"
                }
            }
        }
        println("  boxes: $found inline programs in ${boxes.size} box source(s) compile clean")
    }

    /** A box with the `{{ dust.stereo }}` slot filled and the four force slots bound. */
    private fun boxFor(mode: DustStereoMode, programs: Map<String, String>): Box {
        val box = Box("dust-workout-${mode.name.lowercase()}")
        box.shaderTemplate["dust"] = mapOf("stereo" to mode.shaderSlot)
        programs.forEach { (slot, source) -> box.stackAutoShaderSources[slot] = source }
        return box
    }

    private fun stageOf(stage: DustShaders.ShaderStage): ShaderKind = when (stage) {
        DustShaders.ShaderStage.VERTEX -> ShaderKind.VERTEX_SHADER
        DustShaders.ShaderStage.FRAGMENT -> ShaderKind.FRAGMENT_SHADER
        DustShaders.ShaderStage.COMPUTE -> ShaderKind.COMPUTE_SHADER
    }

    /**
     * Every shader, in both stereo modes, through the real preprocessor and the real compiler.
     *
     * Both modes because they differ by a `#extension` line and a `#define`, which is the smallest
     * possible difference and therefore the easiest to get wrong in only one of eleven shaders. Both
     * program sets because a `stack.auto` slot is textually inlined into the compute shader, so a
     * program that compiles as StackSL can still produce GLSL that does not.
     */
    private fun everyShaderCompilesInBothStereoModes() {
        for (mode in DustStereoMode.entries) {
            for ((label, programs) in listOf("defaults" to DustPrograms.DEFAULTS, "wild" to DustPrograms.WILD)) {
                val box = boxFor(mode, programs)
                for ((name, entry) in DustShaders.ALL) {
                    val (source, stage) = entry
                    val kind = stageOf(stage)
                    val expanded = preprocess(box, source, kind)
                    check("{{" !in expanded) {
                        "$name in $mode/$label still has an unexpanded template slot:\n" +
                            expanded.lines().filter { "{{" in it }.joinToString("\n")
                    }
                    val introspection = Introspection(
                        expanded, kind,
                        errorHandler = { desc, t ->
                            throw IllegalStateException(
                                "$name does not compile in $mode with the $label programs: $desc\n" +
                                    "${t.message}\n--- expanded ---\n${numbered(expanded)}", t
                            )
                        },
                        sourceName = name
                    )
                    check(introspection.valid) { "$name reported invalid in $mode/$label without an error" }

                    // Vertex pulling: an input here would mean a shader had drifted back to an attribute
                    // pipeline while the boxes still only call `resize(n)`.
                    if (kind == ShaderKind.VERTEX_SHADER) check(introspection.inputs.isEmpty()) {
                        "$name declares vertex inputs ${introspection.inputs.map { it.name }}; " +
                            "these draws are vertex-pulled and the meshes supply only a count"
                    }
                }
            }
        }
        println("  shaders: ${DustShaders.ALL.size} compile in ${DustStereoMode.entries.size} stereo modes " +
            "x 2 program sets")
    }

    /**
     * The two stereo modes must differ *only* where the slot is expanded.
     *
     * The whole claim of the design is "one shader, two modes". If some other part of a shader ever
     * starts branching on the mode, this is where it shows up — and the fix is to move the branch into
     * the slot rather than to relax this.
     */
    private fun stereoModesDifferOnlyInTheEyeIndex() {
        val multi = boxFor(DustStereoMode.MULTIVIEW, DustPrograms.DEFAULTS)
        val mono = boxFor(DustStereoMode.MONO, DustPrograms.DEFAULTS)
        val slotLines = (DustShaders.STEREO_MULTIVIEW.lines() + DustShaders.STEREO_MONO.lines())
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        // The preprocessor emits `#line` directives, and the slot's two forms are different lengths, so
        // every line number after it shifts. That is line accounting rather than a shader knowing which
        // mode it is in, so drop those before comparing.
        fun body(text: String) = text.lines()
            .filterNot { it.trimStart().startsWith("#line") }
            .filter { it.isNotBlank() }

        for ((name, entry) in DustShaders.ALL) {
            val (source, stage) = entry
            val kind = stageOf(stage)
            val a = body(preprocess(multi, source, kind))
            val b = body(preprocess(mono, source, kind))
            val onlyInA = (a - b.toSet()).map { it.trim() }
            val onlyInB = (b - a.toSet()).map { it.trim() }
            for (line in onlyInA + onlyInB) check(line in slotLines) {
                "$name differs between stereo modes outside the `{{ dust.stereo }}` slot: '$line'. " +
                    "A shader must not know which mode it is in; move the branch into the slot."
            }
        }
    }

    /**
     * Every push-constant name a box binds, against what the shaders declare.
     *
     * This is `build_starting_point.py`'s refusal-to-write check, done at the type level. The failure it
     * exists for: a box's `points.dotSize = { … }` compiles fine and then throws
     * `can't find dotSize in a mesh in this module` the moment the box runs, because the name reaches
     * the mesh through the *shader's* introspection and nothing checks it earlier.
     *
     * The lists here are the ones `src/personal/resources/dust/box_*.kt` actually bind, so adding a knob
     * to [DustLook] without adding it to a shader fails here rather than in a document.
     */
    private fun pushConstantsTheBoxesBindAreDeclared() {
        val box = boxFor(DustStereoMode.MULTIVIEW, DustPrograms.DEFAULTS)

        fun members(name: String): Map<String, Boolean> {
            val (source, stage) = DustShaders.ALL.getValue(name)
            val kind = stageOf(stage)
            val introspection = Introspection(preprocess(box, source, kind), kind, sourceName = name)
            check(introspection.valid) { "$name did not compile" }
            return introspection.pushConstants.flatMap { it.members }.associate { it.name to it.physicalPointer }
        }

        val shared = listOf("state", "prevState", "links", "history", "count", "frame", "time", "dt")

        // The knobs are `vec4` groups now, not scalars — see the note on DustShaders.DRAW_PUSH. This
        // list is therefore short, and it is the list the boxes in src/personal/resources/dust bind.
        val draw = shared + listOf(
            "vpL", "vpR", "camRight", "camUp", "projScale", "tint", "dot", "ramp", "trail", "stroke"
        )
        val expected = mapOf(
            "dust_force.comp" to shared + listOf(
                "gains", "limits", "gravity", "bounds", "centre", "probe", "knobA", "knobB", "knobC"
            ),
            "dust_history.comp" to shared + listOf("cursor"),
            "dust_point.vert" to draw,
            "dust_edge.vert" to draw,
            "dust_trail.vert" to draw,
            "dust_blur.frag" to listOf("blur", "blurSize"),
            "dust_grade.frag" to listOf("lift", "gain", "gamma", "bloom", "lens")
        )

        for ((shader, names) in expected) {
            val declared = members(shader)
            for (name in names) check(name in declared) {
                "$shader does not declare push member '$name', which a box binds. It declares ${declared.keys}"
            }
        }

        // The four handles must arrive as buffer_reference pointers, or `mesh.state = { buffer }` stops
        // compiling in the box — a confusing place to find out that a shader changed.
        val forceMembers = members("dust_force.comp")
        for (pointer in listOf("state", "prevState", "links", "history")) {
            check(forceMembers.getValue(pointer)) {
                "'$pointer' should be a buffer_reference pointer, not an inline member"
            }
        }

        // A vertex and its fragment share one push range, so their blocks must agree member for member —
        // a mismatch is a pipeline-layout error at module build, not a saving.
        for ((vert, frag) in listOf(
            "dust_point.vert" to "dust_point.frag",
            "dust_edge.vert" to "dust_edge.frag",
            "dust_trail.vert" to "dust_trail.frag"
        )) {
            val v = members(vert).keys
            val f = members(frag).keys
            // The handles are declared as `uvec2 *Address` in the fragment stage on purpose, so compare
            // everything else.
            val vScalar = v.filterNot { it in setOf("state", "prevState", "links", "history") }.toSet()
            val fScalar = f.filterNot { it.endsWith("Address") }.toSet()
            check(vScalar == fScalar) {
                "$vert and $frag disagree on their shared push block: only in vertex ${vScalar - fScalar}, " +
                    "only in fragment ${fScalar - vScalar}"
            }
        }
        println("  push constants: every name the boxes bind is declared, in ${expected.size} shaders")
    }

    /**
     * The one thing about the force shader that is a *correctness* property and not a layout fact.
     *
     * It must read the neighbour positions out of `prevState` and write into `state`. Reading the buffer
     * it is writing is a race between invocations of the same dispatch, and the symptom is not a crash —
     * it is a network that quietly stiffens wherever the scheduler happened to run the neighbours first.
     * Cheap to assert textually, and there is no other way to catch it without a GPU.
     */
    private fun forceShaderReadsPrevStateAndWritesState() {
        val force = mainBody(withoutComments(DustShaders.FORCE_COMPUTE))

        // Every mention of `state` in the force shader must be a write. Reading it is a race with the
        // shader's own dispatch: `state` is where this frame's positions are going, and a neighbour is
        // another invocation of the same dispatch, so a read gets whichever value the scheduler happened
        // to leave there. It does not crash — the network just quietly stiffens where the neighbours ran
        // first, which is unfindable without knowing to look.
        val mentions = Regex("""^(.*?)pc\.state\.p\[""", RegexOption.MULTILINE)
            .findAll(force).map { it.groupValues[1] }.toList()
        check(mentions.isNotEmpty()) { "the force shader never writes pc.state at all" }
        for (before in mentions) check(before.isBlank()) {
            "the force shader reads pc.state (in '${before.trim()}...'); it must read pc.prevState — " +
                "reading the buffer it is writing is a race with its own dispatch"
        }
        check(Regex("""pc\.state\.p\[i]\.(pos|prev|aux)\s*=""").findAll(force).count() == 3) {
            "the force shader should write exactly pos, prev and aux"
        }
        check("pc.prevState.p[i].pos" in withoutComments(DustShaders.DUST_LIB)) {
            "dustPos must read prevState"
        }

        // The history shader is the other way round: it runs after the force dispatch, so the fresh
        // positions are in `state` and `dustPos()` would hand it last frame's. Scoped to `main`, because
        // DUST_LIB is interpolated into this shader too and it is where `dustPos` is *defined*.
        val historyMain = mainBody(withoutComments(DustShaders.HISTORY_COMPUTE))
        check("pc.state.p[i].pos" in historyMain) { "the history shader must read the freshly written pc.state" }
        check("dustPos(" !in historyMain) { "the history shader must not call dustPos(), which reads prevState" }
        check("pc.prevState" !in historyMain) { "the history shader must not read prevState" }
    }

    /**
     * The sign convention, as a property of the integrator rather than as a comment.
     *
     * `gEdgeDir` points from a particle toward its neighbour, so a positive `edgeForce` result pulls the
     * pair together. Under that convention a perturbed spring network released with the shipped Hooke
     * program must **contract**; with the sign flipped it must not. Both halves are asserted, because
     * only the second one fails if the convention silently reverses.
     *
     * This is the assertion that catches the actual mistake, which was writing Hooke's law as `-12 *`.
     * The result on screen was every particle pinned motionless against the bounding box — a picture
     * that reads as a rendering bug, not as a sign error.
     */
    private fun theEdgeForceSignIsRestoring() {
        val field = DustField.random(1200, extent = 1f, seed = 5L)
        val links = DustOrthant.build(field)

        // The gain of the shipped Hooke program, `input x 12 *`. Read out of the string rather than
        // written down twice, so editing the program cannot leave this test asserting the old sign.
        val gain = Regex("""input x (-?[\d.]+) \*""").find(DustPrograms.SPRING)?.groupValues?.get(1)?.toFloat()
        checkNotNull(gain) { "SPRING is no longer `input x <k> *`; this check reads its gain: ${DustPrograms.SPRING}" }
        check(gain > 0f) {
            "SPRING has a negative gain ($gain). Positive is restoring: `input.x` is `len - rest` and " +
                "`gEdgeDir` points at the neighbour, so a stretched edge must return a POSITIVE pull."
        }

        val restoring = DustReference.contractsUnder(field, links, edgeStiffness = gain, restStiffness = 6f)
        check(restoring.contracted) {
            "a network with the shipped edge gain $gain did not contract: $restoring"
        }
        println("  sign: edge gain $gain contracts — $restoring")

        // And the same network with the sign flipped must fail to settle. Without this half, a
        // convention that reversed everywhere at once would still pass.
        val flipped = DustReference.contractsUnder(field, links, edgeStiffness = -gain, restStiffness = 6f)
        check(!flipped.contracted) {
            "flipping the edge sign still contracted, so this test does not constrain the convention: $flipped"
        }
        println("  sign: edge gain ${-gain} does not — $flipped")

        // The rest tether's argument is `rest - pos`, the opposite sense, so its restoring gain is also
        // positive. That asymmetry between the two arguments is the thing a reader gets wrong.
        val tetherGain = Regex("""input xyz (-?[\d.]+) \*""").find(DustPrograms.TETHER)?.groupValues?.get(1)?.toFloat()
        checkNotNull(tetherGain) { "TETHER is no longer `input xyz <k> *`: ${DustPrograms.TETHER}" }
        check(tetherGain > 0f) { "TETHER has a negative gain; its argument is `rest - pos`, so positive pulls home" }

        // With no edges at all, the tether alone must still bring the field home.
        val tetherOnly = DustReference.contractsUnder(field, links, edgeStiffness = 0f, restStiffness = tetherGain)
        check(tetherOnly.endDisplacement < tetherOnly.startDisplacement) {
            "the rest tether alone did not pull the field back toward rest: $tetherOnly"
        }
    }

    /**
     * The bounding box must not have a resting fixed point.
     *
     * The first version reflected the old position's *offset* from the wall, and a particle already
     * sitting exactly on the wall has zero offset — so it got `prev == pos`, zero velocity, and stayed
     * pinned. Reflecting the attempted *step* instead has no such point. Checked textually because there
     * is no way to run the shader without a GPU, and the distinction is one line.
     */
    private fun theBounceHasNoRestingFixedPoint() {
        val body = mainBody(withoutComments(DustShaders.FORCE_COMPUTE))
        check("float attempt = next[a] - keep[a];" in body) {
            "the wall bounce must reflect the attempted step; reflecting the old offset from the wall " +
                "pins a particle that is already resting on it (prev == pos, velocity 0, forever)"
        }
        check("keep[a] = wall + attempt * DUST_ELASTICITY;" in body) {
            "the reflected previous position must be `wall + attempt * elasticity`"
        }
        check("(keep[a] - lo[a]) * -" !in body) { "the old offset-reflecting bounce is back" }
    }

    /**
     * The point sprite must be built in world space, along the camera basis.
     *
     * The mistake this replaces was `clip.xy += q * radius * vp[0][0..1]`, which is wrong in a way that
     * reads as correct: `vp` is `projection * view`, so `vp[0][0]` is the projection's x scale *mixed
     * with the view rotation*. It is very nearly right while the camera sits still and visibly wrong the
     * moment it turns — the sprites shear and keep their original orientation while the scene rotates.
     * Reported from the running document, not caught here, which is why it is pinned here now.
     */
    private fun theBillboardIsBuiltInWorldSpace() {
        val point = mainBody(withoutComments(DustShaders.POINT_VERTEX))
        check("dustBillboard(pos.xyz, q, radius)" in point) {
            "the point shader must build its quad with dustBillboard(), in world space along the camera basis"
        }
        for (bad in listOf("vp[0][0]", "vp[1][1]", "clip.x +=", "clip.y +=")) {
            check(bad !in point) {
                "the point shader is back to offsetting clip space by `$bad`. `vp` is projection*view, " +
                    "so its diagonal is not the projection's scale — the sprites shear when the camera turns."
            }
        }

        val lib = withoutComments(DustShaders.DRAW_LIB)
        check("world + (q.x * pc.camRight.xyz + q.y * pc.camUp.xyz) * radius" in lib) {
            "dustBillboard must offset along camRight/camUp"
        }
        // The pixel floor is the one place the projection's own scale is still needed, and it must come
        // from projScale rather than from vp.
        check("abs(dustProjScale().y)" in lib) { "the radius floor must use the projection's own p11" }
        check("pc.projScale.xy" in lib && "pc.projScale.zw" in lib) {
            "dustProjScale must select the eye's half of the projScale member"
        }
    }

    /**
     * The default noise program must sample a genuinely three-dimensional field.
     *
     * StackSL's `fbm` and `noise` lower to `fbm(vec2)` over `coord.xy + coord.zw * 0.07`. Handing them
     * `vec4(pos.xyz, time) * k` — the obvious thing — gives a coordinate of
     * `(k·x + 0.07k·z, k·y + 0.07k·t)`: `x` at fourteen times the weight of `z`, and `z` entering only
     * as a shear on the x axis. The field is then essentially `f(x, y)`, and on a 3D cloud viewed down
     * z that shows as a fixed stripy pattern printed at every depth — reported from the running
     * document, in the edge strain, where it looked like structure and was flat noise.
     *
     * The fix is three slices, `f(x,y) + f(y,z) + f(z,x)`, so this checks that the generated GLSL feeds
     * `fbm` from more than one swizzle of the input and that `.yz` or `.zx` is among them. That is the
     * property: `z` has to reach a *primary* coordinate, not just the `0.07` term.
     *
     * [DustPrograms.DRIFT_FLAT] is exempt and asserted to be flat, so the comparison it exists for
     * cannot silently stop being a comparison.
     */
    private fun theDefaultNoiseIsThreeDimensional() {
        fun slicesOf(program: String): Set<String> {
            val glsl = afield.stacksl.compileStackSLToGlsl(
                source = program, prefix = "n", declareSamplers = false
            )
            // Each slice arrives as `vec4 _n_vN = vec4(n_in.<swizzle>, 0.0, 1.0);`
            return Regex("""vec4\(n_in\.(\w+),""").findAll(glsl).map { it.groupValues[1] }.toSet()
        }

        val three = slicesOf(DustPrograms.DRIFT)
        check(three.size > 1) {
            "the default noise program feeds fbm from a single swizzle ($three), so it is a 2D field " +
                "printed at every z. StackSL's fbm is fbm(vec2) — sum three slices instead."
        }
        check(three.any { it == "yz" || it == "zx" || it == "xz" || it == "zy" }) {
            "no slice of the default noise program carries z as a primary coordinate (slices: $three); " +
                "z reaching only the `coord.zw * 0.07` term is a 14:1 anisotropy, not a third dimension"
        }

        // The flat program feeds `fbm` the whole input unswizzled, so it takes exactly one coordinate
        // and z reaches it only through the `* 0.07` term. Assert that directly: an empty slice set
        // would otherwise pass a "no bad slices" test for the wrong reason.
        val flatGlsl = afield.stacksl.compileStackSLToGlsl(
            source = DustPrograms.DRIFT_FLAT, prefix = "n", declareSamplers = false
        )
        check(slicesOf(DustPrograms.DRIFT_FLAT).isEmpty() && "n_fbm(_n_v" in flatGlsl) {
            "DRIFT_FLAT is the flat program the 3D one is compared against; it should feed fbm the " +
                "unswizzled input, and it no longer does"
        }
        println("  noise: default samples slices $three; DRIFT_FLAT stays 2D for comparison")
    }

    /**
     * Every `#define` in the lane blocks must address a member the push block declares, and no two may
     * address the same lane.
     *
     * Grouping knobs into `vec4`s buys keyframability and costs exactly one kind of mistake: two names
     * quietly meaning the same slot, so setting one clobbers the other. That is invisible in the GLSL
     * and invisible in the picture until two parameters move together. It is also trivially checkable,
     * which is the whole reason the lane names are `#define`s in one block rather than scattered.
     */
    private fun everyLaneNameIsUniqueAndDeclared() {
        // `#define NAME pc.group.lane`, and the wrapped integer form `#define NAME uint(pc.group.lane + 0.5)`.
        val define = Regex("""^#define\s+(\w+)\s+.*?pc\.(\w+)\.(\w+)""", RegexOption.MULTILINE)

        for ((label, lanes, push) in listOf(
            Triple("draw", DustShaders.DUST_DRAW_LANES, DustShaders.DRAW_PUSH),
            Triple("force", DustShaders.DUST_FORCE_LANES, DustShaders.FORCE_PUSH),
            Triple("history", DustShaders.DUST_HISTORY_LANES, DustShaders.HISTORY_PUSH)
        )) {
            val declared = Regex("""^\s*(?:vec4|mat4)\s+(\w+);""", RegexOption.MULTILINE)
                .findAll(push).map { it.groupValues[1] }.toSet()

            val seen = mutableMapOf<Pair<String, String>, String>()
            var found = 0
            for (m in define.findAll(lanes)) {
                val (name, group, lane) = m.destructured
                found++
                check(group in declared) {
                    "$label lane $name addresses pc.$group, which its push block does not declare " +
                        "(declared: ${declared.sorted()})"
                }
                // A swizzle like `.xy` is a legitimate multi-lane read; only single lanes can alias.
                if (lane.length != 1) continue
                val previous = seen.put(group to lane, name)
                check(previous == null) {
                    "$label lanes $previous and $name both mean pc.$group.$lane — setting one clobbers the other"
                }
            }
            check(found > 0) { "no lane names found in the $label block" }
        }
    }

    /**
     * A handful of factory-to-lane assignments, checked by value.
     *
     * The `#define`s say which lane a name means; the factories in `DustParams.kt` decide which lane a
     * named argument lands in. Nothing makes those agree, and a swapped pair of arguments is silent —
     * `dustGains(edge = …, noise = …)` writing noise into `x` would just make two knobs trade places.
     * Only the groups where a swap would be quiet are listed; the point is the coupling, not coverage.
     */
    private fun laneAssignmentsMatchTheFactories() {
        val lanes = "xyzw"
        fun laneOf(block: String, name: String): Pair<String, Int> {
            val m = Regex("""^#define\s+${Regex.escape(name)}\s+.*?pc\.(\w+)\.([xyzw])""", RegexOption.MULTILINE)
                .find(block) ?: error("no lane named $name")
            return m.groupValues[1] to lanes.indexOf(m.groupValues[2][0])
        }
        fun component(v: afield.stdlib.Vec4, i: Int) = when (i) { 0 -> v.x; 1 -> v.y; 2 -> v.z; else -> v.w }

        // Distinct probe values, so a swap cannot pass by coincidence.
        val gains = dustGains(edge = 11f, noise = 22f, rest = 33f, damping = 44f)
        val limits = dustLimits(maxStep = 11f, maxForce = 22f, edgeSlots = 3, paused = true)
        val ramp = dustRamp(speed = 11f, strain = 22f, fade = 33f, mode = DUST_COLOR_DEGREE)
        val dot = dustDot(size = 11f, minPixels = 22f, viewportHeight = 33f)
        val trail = dustTrail(capacity = 11, head = 22, written = 33, everyNth = 44)
        val stroke = dustStroke(trailFade = 11f, everyNth = 22)

        val cases = listOf(
            Triple(DustShaders.DUST_FORCE_LANES, "DUST_EDGE_GAIN", 11f to gains),
            Triple(DustShaders.DUST_FORCE_LANES, "DUST_NOISE_GAIN", 22f to gains),
            Triple(DustShaders.DUST_FORCE_LANES, "DUST_REST_GAIN", 33f to gains),
            Triple(DustShaders.DUST_FORCE_LANES, "DUST_DAMPING", 44f to gains),
            Triple(DustShaders.DUST_FORCE_LANES, "DUST_MAX_STEP", 11f to limits),
            Triple(DustShaders.DUST_FORCE_LANES, "DUST_MAX_FORCE", 22f to limits),
            Triple(DustShaders.DUST_FORCE_LANES, "DUST_EDGE_SLOTS", 3f to limits),
            Triple(DustShaders.DUST_FORCE_LANES, "DUST_PAUSED", 1f to limits),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_SPEED_GAIN", 11f to ramp),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_STRAIN_GAIN", 22f to ramp),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_FADE", 33f to ramp),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_COLOR_MODE", DUST_COLOR_DEGREE.toFloat() to ramp),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_DOT_SIZE", 11f to dot),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_DOT_MIN_PIXELS", 22f to dot),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_VIEWPORT_H", 33f to dot),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_CAPACITY", 11f to trail),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_HEAD", 22f to trail),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_WRITTEN", 33f to trail),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_TRAIL_EVERY", 44f to trail),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_TRAIL_FADE", 11f to stroke),
            Triple(DustShaders.DUST_DRAW_LANES, "DUST_EVERY_NTH", 22f to stroke)
        )

        for ((block, name, expectation) in cases) {
            val (want, value) = expectation
            val (group, lane) = laneOf(block, name)
            val got = component(value, lane)
            check(got == want) {
                "$name is pc.$group.${lanes[lane]}, but the factory put $got there and not $want — " +
                    "the shader's lane names and DustParams.kt disagree about which argument goes where"
            }
        }
        println("  lanes: ${cases.size} factory-to-lane assignments agree")
    }

    /**
     * GLSL with its comments removed, so a check about what a shader *does* is not satisfied or defeated
     * by what a shader *says*. The history check above failed on its own explanatory comment once.
     */
    private fun withoutComments(glsl: String): String =
        glsl.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    /**
     * Just the shader's own `main`, without the shared library interpolated above it.
     *
     * `DUST_LIB` is pasted into every stage, so a check for "does this shader call `dustPos`" run over
     * the whole text finds the *definition* and fails whatever the shader does.
     */
    private fun mainBody(glsl: String): String {
        val at = glsl.indexOf("void main()")
        check(at >= 0) { "no main() in:\n${numbered(glsl)}" }
        return glsl.substring(at)
    }

    private fun numbered(source: String): String =
        source.lines().mapIndexed { i, l -> "%4d  %s".format(i + 1, l) }.joinToString("\n")
}
