package afield.graphics.render

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.util.gpuProbe
import afield.math.linalg.Vector3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.splat-tiles.gpu
requiresDisplay: true
runner: jvm-main
entrypoint: afield.graphics.render.SplatTilesGpuProbe
tags: [smoke, four, gpu, compute, probe, splat, raster]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "splat tiles gpu PASSED"
*/
/**
 * The per-tile blend of [SplatTiles] as a Vulkan compute shader, checked pixel for pixel against
 * the CPU version and timed under the two schedules.
 *
 * Binning stays on the CPU ([SplatTiles.bin]); what runs on the GPU is the part arXiv:2611.12319 is
 * about, the blend. One 16×16 workgroup blends one work item, one invocation per pixel, front to
 * back with early termination. Two dispatch shapes:
 *
 * - **naive**: one workgroup per tile, tiles in raster order, `gl_WorkGroupID` picks the tile.
 * - **balanced**: a persistent kernel. A fixed number of workgroups loop, each taking the next item
 *   from an atomic counter over [SplatTiles.Schedule.Balanced]'s heaviest-first list, with outlier
 *   tiles already cut into chunks; the CPU merges the chunks with [SplatTiles.assemble].
 *
 * Timing is wall-clock per frame over [TIMED_FRAMES] frames, once with one blend per frame and
 * once with [REPEATS]; the difference divided by `REPEATS - 1` is the blend's cost with the frame
 * loop's own overhead cancelled. The persistent kernel's queue is reset by a dispatch of the same
 * stage between blends, and the probe checks the last blend drained it.
 */
object SplatTilesGpuProbe {

    private const val TILE = SplatTiles.TILE
    private const val PIXELS = TILE * TILE

    /** Blend dispatches per frame in the heavy run; the light run has one. */
    private const val REPEATS = 17

    /** Frames timed per measurement. */
    private const val TIMED_FRAMES = 40

    /** Persistent workgroups for the balanced kernel. */
    private const val PERSISTENT_GROUPS = 256

    private const val BLEND_GLSL = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer Vec4s { vec4 v[]; };
layout(std430, buffer_reference) buffer Uints { uint v[]; };
layout(local_size_x = 16, local_size_y = 16) in;

layout(push_constant) uniform Push {
    Vec4s splats;   // 4 vec4 per projected splat
    Uints entries;  // per-tile lists, front to back
    Uints items;    // 4 uints per item: tile, start, end, unused
    Vec4s outputs;  // 256 vec4 per item: premultiplied rgb, transmittance
    Uints counter;  // [0] the next item, for the persistent kernel
    uint itemCount;
    uint tilesX;
    uint width;
    uint height;
    uint persistent;
    uint resetOnly;
    float cutoff;
    float alphaCutoff;
} pc;

shared uint nextItem;

void blendItem(uint item) {
    uint tile = pc.items.v[item * 4u];
    uint start = pc.items.v[item * 4u + 1u];
    uint end = pc.items.v[item * 4u + 2u];
    int col = int((tile % pc.tilesX) * 16u + gl_LocalInvocationID.x);
    int row = int((tile / pc.tilesX) * 16u + gl_LocalInvocationID.y);
    vec3 c = vec3(0.0);
    float t = 1.0;
    bool inside = col < int(pc.width) && row < int(pc.height);
    float dy = float(pc.height) - float(row) - 0.5;
    float px = float(col) + 0.5;
    for (uint e = start; inside && e < end && t >= pc.cutoff; e++) {
        uint s = pc.entries.v[e];
        vec4 a = pc.splats.v[s * 4u];
        vec4 b = pc.splats.v[s * 4u + 1u];
        vec4 colour = pc.splats.v[s * 4u + 2u];
        vec4 bounds = pc.splats.v[s * 4u + 3u];
        if (float(col) < bounds.x || float(col) > bounds.y || float(row) < bounds.z || float(row) > bounds.w) continue;
        float dx = px - a.x;
        float ddy = dy - a.y;
        float u = (dx * a.z + ddy * a.w) * b.z;
        float v = (dx * b.x + ddy * b.y) * b.w;
        if (abs(u) > 3.0 || abs(v) > 3.0) continue;
        float alpha = clamp(colour.w * exp(-0.5 * (u * u + v * v)), 0.0, 1.0);
        if (alpha < pc.alphaCutoff) continue;
        c += colour.rgb * (t * alpha);
        t *= 1.0 - alpha;
    }
    pc.outputs.v[item * 256u + gl_LocalInvocationIndex] = vec4(c, t);
}

void main() {
    // A reset dispatch in the same stage, so it is ordered between blends by the stage's barriers.
    if (pc.resetOnly != 0u) {
        if (gl_WorkGroupID.x == 0u && gl_LocalInvocationIndex == 0u) pc.counter.v[0] = 0u;
        return;
    }
    if (pc.persistent == 0u) {
        if (gl_WorkGroupID.x < pc.itemCount) blendItem(gl_WorkGroupID.x);
        return;
    }
    while (true) {
        if (gl_LocalInvocationIndex == 0u) nextItem = atomicAdd(pc.counter.v[0], 1u);
        barrier();
        uint item = nextItem;
        barrier();
        if (item >= pc.itemCount) break;
        blendItem(item);
    }
}
"""

    /** One framing to check and time. */
    data class Population(val width: Int, val height: Int, val eye: Vector3f, val count: Int, val label: String)

    val populations = listOf(
        Population(1280, 720, Vector3f(0f, 0.3f, 2.6f), 200_000, "whole cloud 1280x720"),
        Population(1280, 720, Vector3f(0f, 0.05f, 0.6f), 200_000, "close on the core 1280x720"),
        Population(96, 96, Vector3f(0f, 0.3f, 2.6f), 200_000, "whole cloud 96x96")
    )

    /** Largest per-channel 8-bit difference allowed between GPU and CPU tiles. */
    private const val MAX_CODE_DELTA = 2

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.vsync = false
        val window = Window("Splat Tiles GPU Probe")
        val renderer = Renderer(window)
        var failures = 0
        val pump = { window.pollEvents(); renderer.render() }
        val table = mutableListOf<String>()
        try {
            for (p in populations) {
                val view = SplatRaster.View.lookAt(eye = p.eye, target = Vector3f(0f, 0f, 0f), width = p.width, height = p.height)
                val binning = SplatTiles.bin(SplatTiles.clusteredCloud(p.count), p.count, view)
                val cpu = SplatTiles.render(binning, SplatTiles.Schedule.Naive, transmittanceCutoff = 1e-4f).image.toArgb()
                println("${p.label}: $binning")
                for (balanced in listOf(false, true)) {
                    val name = if (balanced) "balanced" else "naive"
                    // Frame time with 1 blend and with REPEATS blends; the difference over
                    // REPEATS - 1 is the blend's own cost, whatever the frame loop adds.
                    val ms = DoubleArray(2)
                    var items = 0
                    for ((k, repeats) in listOf(1, REPEATS).withIndex()) {
                        failures += gpuProbe(renderer, "splat-$name-$repeats", pump = pump) { probe ->
                            val (frameMs, n) = blend(probe, binning, balanced, cpu, p.label, repeats)
                            ms[k] = frameMs; items = n
                            probe.verdict("splat tiles gpu $name x$repeats ${p.label}")
                        }
                    }
                    table += "%-28s %-9s %8.3f ms per blend  %6d items  (%d pairs, max tile %d)".format(
                        p.label, name, (ms[1] - ms[0]) / (REPEATS - 1), items, binning.pairs, binning.maxCount
                    )
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            failures++
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        println("--- GPU blend timing ((frame ms with $REPEATS blends - frame ms with 1) / ${REPEATS - 1}):")
        table.forEach { println("  $it") }
        if (failures > 0) {
            println("splat tiles gpu FAILED ($failures)")
            exitProcess(1)
        }
        println("splat tiles gpu PASSED")
        exitProcess(0)
    }

    private fun timeFrames(probe: GpuProbe): Double {
        probe.pumpFrames(5)
        val t0 = System.nanoTime()
        probe.pumpFrames(TIMED_FRAMES)
        probe.idle()
        return (System.nanoTime() - t0) / 1e6 / TIMED_FRAMES
    }

    /** Upload, dispatch, check against [cpu], and return the timed frame ms. */
    private fun blend(
        probe: GpuProbe, binning: SplatTiles.Binning, balanced: Boolean, cpu: IntArray, label: String, repeats: Int
    ): Pair<Double, Int> {
        val schedule = if (balanced) SplatTiles.Schedule.Balanced() else SplatTiles.Schedule.Naive
        // The chunk is sized for 32 GPU cores' worth of workers rather than CPU threads.
        val (itemTile, itemStart, itemEnd) = SplatTiles.workItems(binning, schedule, 32)
        val items = itemTile.size

        val splatFloats = FloatArray(max(1, binning.drawable) * 16)
        for (s in 0 until binning.drawable) {
            val o = s * 15
            val p = binning.projected
            val d = s * 16
            splatFloats[d] = p[o]; splatFloats[d + 1] = p[o + 1]; splatFloats[d + 2] = p[o + 2]; splatFloats[d + 3] = p[o + 3]
            splatFloats[d + 4] = p[o + 4]; splatFloats[d + 5] = p[o + 5]; splatFloats[d + 6] = p[o + 6]; splatFloats[d + 7] = p[o + 7]
            splatFloats[d + 8] = p[o + 8]; splatFloats[d + 9] = p[o + 9]; splatFloats[d + 10] = p[o + 10]; splatFloats[d + 11] = p[o + 11]
            val b = s * 4
            splatFloats[d + 12] = binning.bounds[b].toFloat(); splatFloats[d + 13] = binning.bounds[b + 1].toFloat()
            splatFloats[d + 14] = binning.bounds[b + 2].toFloat(); splatFloats[d + 15] = binning.bounds[b + 3].toFloat()
        }
        val itemWords = FloatArray(items * 4)
        for (i in 0 until items) {
            itemWords[i * 4] = Float.fromBits(itemTile[i])
            itemWords[i * 4 + 1] = Float.fromBits(itemStart[i])
            itemWords[i * 4 + 2] = Float.fromBits(itemEnd[i])
        }
        val entryWords = FloatArray(max(1, binning.pairs)) { Float.fromBits(binning.entries.getOrElse(it) { 0 }) }

        val splats = probe.fixture("splats", splatFloats.size)
        val entries = probe.fixture("entries", entryWords.size)
        val itemBuf = probe.fixture("items", itemWords.size)
        val outputs = probe.fixture("outputs", items * PIXELS * 4)
        val counter = probe.fixture("counter", 4)
        probe.upload(splats, splatFloats)
        probe.upload(entries, entryWords)
        probe.upload(itemBuf, itemWords)
        probe.fill(counter, 0f)

        val stage = probe.stage("blend", BLEND_GLSL)
        repeat(repeats) { r ->
            for (resetOnly in listOf(true, false)) {
                if (resetOnly && !balanced) continue
                stage.dispatch(
                    groups = if (resetOnly) 1 else if (balanced) PERSISTENT_GROUPS else items,
                    label = if (resetOnly) "reset$r" else "blend$r",
                ) {
                    bind("splats") { splats }
                    bind("entries") { entries }
                    bind("items") { itemBuf }
                    bind("outputs") { outputs }
                    bind("counter") { counter }
                    bind("itemCount") { items }
                    bind("tilesX") { binning.tilesX }
                    bind("width") { binning.width }
                    bind("height") { binning.height }
                    bind("persistent") { if (balanced) 1 else 0 }
                    bind("resetOnly") { if (resetOnly) 1 else 0 }
                    bind("cutoff") { 1e-4f }
                    bind("alphaCutoff") { binning.alphaCutoff }
                }
            }
        }
        probe.settle(outputs)
        for (s in probe.stageList) if (!s.valid) probe.fail("stage '${s.stageName}' never became valid")

        val raw = probe.read(outputs)
        probe.allFinite("outputs", raw, buffer = "outputs")
        val partial = arrayOfNulls<FloatArray>(items)
        for (i in 0 until items) partial[i] = raw.copyOfRange(i * PIXELS * 4, (i + 1) * PIXELS * 4)
        val gpu = SplatTiles.assemble(binning, itemTile, itemStart, partial).toArgb()
        var worst = 0
        var differing = 0
        for (i in cpu.indices) {
            var d = 0
            for (shift in intArrayOf(16, 8, 0)) d = max(d, abs(((cpu[i] shr shift) and 255) - ((gpu[i] shr shift) and 255)))
            if (d > 0) differing++
            worst = max(worst, d)
        }
        println("  ${if (balanced) "balanced" else "naive"}: $items items, worst delta $worst, $differing of ${cpu.size} pixels differ")
        probe.atMost("worst 8-bit delta vs CPU tiles ($label)", worst, MAX_CODE_DELTA, buffer = "outputs")
        // The queue must have been drained by every repeat, not only the first: after the last
        // reset and blend the counter sits just past the end, by one per persistent workgroup.
        if (balanced) {
            val taken = probe.readInts(counter, 1)[0]
            probe.exactly("queue drained by the last blend", taken, items + PERSISTENT_GROUPS, buffer = "counter")
        }
        return timeFrames(probe) to items
    }
}
