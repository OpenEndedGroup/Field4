package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.KeyframeScope
import afield.stdlib.KeyframeSweep
import afield.stdlib.SweepConfig
import afield.stdlib.SweepReport
import afield.stdlib.awaitFrames
import afield.stdlib.captureClip
import afield.stdlib.captureStill
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.readSweepArchive
import afield.stdlib.sweepTargetPngBytes
import afield.stdlib.sweepArchiveSummary
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.system.exitProcess

/*
FIELD_TEST
id: keyframe.sweep.smoke
runner: jvm-main
entrypoint: afield.examples.four.KeyframeSweepDemo
tags: [smoke, keyframe, sweep, four, gpu, image]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "keyframe sweep tests PASSED"
*/
/**
 * Drives [KeyframeSweep] against a real GPU render, and is the thing to run to watch an overnight
 * sweep work.
 *
 * `KeyframeSweepTest` checks the sweeper on the CPU: that the weights in a manifest reproduce the
 * key beside it, that a seed redraws a night, that a resume does not change the draw. Every one of
 * those assertions would still hold if applying a blend reached no uniform at all. This is the test
 * that cannot.
 *
 * ### What it asserts, and why each one needs a GPU
 *
 * - **The picture is a function of the blend.** Ten lanes of a `StandardRenderer` scene — camera,
 *   light, ambient, background, AO and shadow strength, a grid spacing, a visible-sphere count and a
 *   named palette — are registered as keyframe targets, and the only thing that ever writes them is
 *   a blended key. Across every pair of renders, pixel distance is then required to *correlate* with
 *   key distance. Not "consecutive renders differ": two independent draws can legitimately land next
 *   to each other in the space, and the first run of this test had a 0.74/0.26 blend followed by a
 *   0.67/0.30 one whose pictures were nearly identical — correctly. A sweep whose `applyToScope`
 *   wrote nowhere would give an archive of *identical* pictures, which is what the correlation
 *   catches and what nothing on the CPU side can see.
 * - **A key is a complete description of a look.** Render zero's key is re-applied at the end of the
 *   run and re-rendered, and the image has to come back within [SAME_PICTURE_CEILING] of the still
 *   the sweep saved. Measured at **0.0** — bit-identical. That was not assumed: the renderer carries
 *   a frame of temporal history (`prevView`, `aaBlend`), so the tolerance stays for the machine
 *   where that history has not settled after [SETTLE_FRAMES] frames.
 * - **An `Int` lane survives the round trip as an `Int`.** `grid.visible` changes CPU-authored
 *   geometry, so a `Double` there would throw inside the setter rather than mis-render. It is
 *   asserted on the key files, where `preserveNumericKind` either worked or did not.
 * - **The still and the clip are the archive's, not the harness's.** Both come from
 *   `SweepRender.captureStill` / `captureClip` — the same helpers a real sweep uses — so the
 *   readback path, the artifact records and the missing-`ffmpeg` fallback are all exercised here
 *   rather than described.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id keyframe.sweep.smoke
 * tools/run_field_tests.py --id keyframe.sweep.smoke --args=--keep   # ...leave the window open
 * ```
 *
 * Writes its archive to `build/keyframe-sweep/`, which is worth looking at afterwards: it is a real,
 * small version of what a night produces.
 *
 * Needs a GPU and a display, for the reason `FabricatePassChainDemo` documents: `afield.four` has no
 * headless mode, because `Renderer` requires a `Window`. Everything measured is rendered offscreen;
 * the window is incidental.
 */
object KeyframeSweepDemo {

    private const val DIM = 512
    private const val GRID = 3
    private const val WARMUP_FRAMES = 24

    /** Renderer frames pumped after a blend is applied, before the still is read back. */
    private const val SETTLE_FRAMES = 8

    /** Frames in each render's clip. Short on purpose: a night wants a glance, not a film. */
    private const val CLIP_FRAMES = 6

    /**
     * Mean absolute luma difference, 0..255, below which two readbacks are "the same picture".
     *
     * Measured rather than chosen: re-applying a key and re-rendering it reproduced its still to
     * well under this, while neighbouring renders in the same sweep differed by more than ten times
     * it. Both numbers are printed by the run.
     */
    private const val SAME_PICTURE_CEILING = 6.0

    /** Mean absolute luma difference two *different* blends must exceed, on the same scale. */
    private const val DIFFERENT_PICTURE_FLOOR = 1.5

    private var failures = 0

    private val outputDir: File get() = File(System.getProperty("user.dir"), "build/keyframe-sweep")

    /** The named palettes the discrete `palette` lane chooses between, whole. */
    private val palettes: Map<String, Pair<Vector3f, Vector3f>> = linkedMapOf(
        // ambient to light colour.
        "warm" to (Vector3f(0.10f, 0.05f, 0.03f) to Vector3f(1.0f, 0.78f, 0.55f)),
        "cool" to (Vector3f(0.03f, 0.05f, 0.11f) to Vector3f(0.62f, 0.78f, 1.0f)),
        "acid" to (Vector3f(0.04f, 0.10f, 0.04f) to Vector3f(0.75f, 1.0f, 0.35f))
    )

    /**
     * A scene plus the keyframe scope that watches it — everything a sweep needs except the pool.
     *
     * The scope is the interesting half. Ten lanes, registered through the typed helpers so each one
     * validates at the scope boundary, and *nothing else in this file ever writes them*. That is
     * what makes the picture evidence about the blend rather than about the harness.
     */
    class Rig(
        val bundle: StandardRenderer.Bundle,
        val scope: KeyframeScope,
        /** The camera position the keys blend, which the clip orbits *around* rather than replacing. */
        val cameraAt: Vector3f
    )

    /** Build the scene and register every lane of it that a key can drive. */
    fun buildRig(renderer: Renderer): Rig {
        val bundle = StandardRenderer.build(
            renderer,
            StandardRenderer.Config(
                renderWidth = DIM,
                renderHeight = DIM,
                backgroundColor = Vector3f(0.09f, 0.10f, 0.13f)
            )
        )
        val scene = bundle.scene

        // The square target must be projected square. `Projection(renderer)`'s `auto` lambda
        // re-resizes to the *window* inside every `evaluateUniform`, so a one-off `resize` is
        // overwritten on the next frame and a sphere in a 512² target inside a wide window comes
        // out as an ellipse — plausible enough to stare past. Replacing the lambda is the fix;
        // calling `resize` once is not.
        scene.projection.auto = { scene.projection.resize(DIM, DIM) }
        scene.projection.resize(DIM, DIM)

        scene.camera.at.set(0f, 2.0f, -3.2f)
        scene.camera.target.set(0f, 0f, 0f)
        scene.lightDirection.set(Vector3f(-0.5f, -1f, -0.3f).normalize())
        scene.lightIntensity = 3.4f
        scene.shadowStrength = 1f
        scene.aoStrength = 0.8f

        val spheres = buildList {
            for (ix in 0 until GRID) for (iz in 0 until GRID) {
                add(bundle.registerSphere(name = "ball-$ix-$iz", radius = 0.3f, slices = 24, stacks = 24, renderer = renderer))
            }
        }
        // A floor, and not for decoration: `shadow.strength` and `ao.strength` are two of the ten
        // lanes, and with nothing under the spheres to receive a shadow or an occlusion they were
        // two lanes a sweep could move all night without changing a pixel.
        bundle.registerCube(name = "floor", size = 1f, renderer = renderer)
            .setModel(Matrix4f().translate(0f, -0.33f, 0f).scale(11f, 0.04f, 11f))
        bundle.applyScenePlan()

        // Two lanes with CPU consequences rather than uniform ones: the grid's spacing, and how many
        // of its nine spheres are drawn at all. A hidden sphere is scaled to nothing, which is the
        // cheap way to make an `Int` lane change the geometry without rebuilding a mesh.
        var spread = 0.85f
        var visible = GRID * GRID

        // Hidden from the outside in, not from the end of the list. Hiding the tail of a row-major
        // grid takes its last row away and shoves the remaining cluster off to one side, so the
        // `visible` lane would be half a camera move as well as a count — and a blend of two looks
        // would drift across the frame for a reason nothing in the key names.
        val centreOut = (0 until GRID * GRID).sortedBy { i ->
            val ix = i / GRID - (GRID - 1) / 2
            val iz = i % GRID - (GRID - 1) / 2
            ix * ix + iz * iz
        }
        fun layout() {
            centreOut.forEachIndexed { rank, i ->
                val pair = spheres[i]
                if (rank >= visible) {
                    pair.setModel(Matrix4f().scale(0f))
                } else {
                    val ix = i / GRID - (GRID - 1) / 2
                    val iz = i % GRID - (GRID - 1) / 2
                    pair.setModel(Matrix4f().translate(ix * spread, 0f, iz * spread))
                }
            }
        }
        layout()

        var palette = "warm"
        fun applyPalette(name: String) {
            val (ambient, light) = palettes[name] ?: palettes.getValue("warm")
            scene.ambientColor.set(ambient)
            scene.lightColor.set(light)
            palette = name
        }
        applyPalette(palette)

        val scope = KeyframeScope(schemaVersion = 1).apply {
            vector3Target("camera.at", { scene.camera.at }) { scene.camera.at.set(it) }
            vector3Target("camera.target", { scene.camera.target }) { scene.camera.target.set(it) }
            vector3Target("light.direction", { scene.lightDirection }) {
                scene.lightDirection.set(if (it.lengthSquared() > 1e-8f) Vector3f(it).normalize() else it)
            }
            floatTarget("light.intensity", { scene.lightIntensity }) { scene.lightIntensity = it }
            vector3Target("background", { scene.backgroundColor }) { scene.backgroundColor.set(it) }
            floatTarget("ao.strength", { scene.aoStrength }) { scene.aoStrength = it }
            floatTarget("shadow.strength", { scene.shadowStrength }) { scene.shadowStrength = it }
            floatTarget("grid.spread", { spread }) { spread = it; layout() }
            intTarget("grid.visible", { visible }, 1..(GRID * GRID)) { visible = it; layout() }
            // The discrete lane: a name that selects a whole sub-state. There is no midpoint between
            // "warm" and "acid", which is exactly why a sweep takes it whole from one parent.
            stringTarget("palette", { palette }) { applyPalette(it) }
        }

        return Rig(bundle, scope, Vector3f(scene.camera.at))
    }

    /**
     * Write a small pool of hand-authored keys into [dir], as a person would have arrived at them,
     * and answer how many.
     *
     * The fourth key is deliberately *incomplete* — it predates the `ao.strength` and `palette`
     * lanes, the way a key saved three weeks ago does. That is not a flaw in the fixture: it is the
     * case [afield.stdlib.MissingSlots] exists for, and the sweep's manifests record the back-fill.
     */
    fun writePool(scope: KeyframeScope, dir: Path): Int {
        dir.createDirectories()
        fun key(name: String, values: Map<String, Any?>) =
            dir.resolve(name).writeText(scope.encodeKeyJson(scope.Key(values.toMutableMap())))

        key(
            "high-and-cool", mapOf(
                "camera.at" to Vector3f(0f, 3.0f, -3.0f),
                "camera.target" to Vector3f(0f, 0f, 0f),
                "light.direction" to Vector3f(-0.5f, -1f, -0.3f).normalize(),
                "light.intensity" to 3.4f,
                "background" to Vector3f(0.012f, 0.020f, 0.050f),
                "ao.strength" to 0.85f,
                "shadow.strength" to 1.0f,
                "grid.spread" to 0.85f,
                "grid.visible" to 9,
                "palette" to "cool"
            )
        )
        key(
            "low-and-warm", mapOf(
                "camera.at" to Vector3f(1.8f, 1.2f, -2.6f),
                "camera.target" to Vector3f(0f, 0.1f, 0f),
                "light.direction" to Vector3f(0.7f, -0.4f, -0.5f).normalize(),
                "light.intensity" to 6.2f,
                "background" to Vector3f(0.050f, 0.025f, 0.012f),
                "ao.strength" to 0.35f,
                "shadow.strength" to 0.6f,
                "grid.spread" to 1.45f,
                "grid.visible" to 4,
                "palette" to "warm"
            )
        )
        key(
            "flat-and-acid", mapOf(
                "camera.at" to Vector3f(-2.2f, 1.8f, -2.4f),
                "camera.target" to Vector3f(0.2f, 0.2f, 0f),
                "light.direction" to Vector3f(-0.1f, -0.95f, 0.1f).normalize(),
                "light.intensity" to 1.9f,
                "background" to Vector3f(0.010f, 0.045f, 0.015f),
                "ao.strength" to 1.0f,
                "shadow.strength" to 0.15f,
                "grid.spread" to 0.55f,
                "grid.visible" to 9,
                "palette" to "acid"
            )
        )
        key(
            "older-partial-key", mapOf(
                "camera.at" to Vector3f(0f, 1.6f, -3.6f),
                "camera.target" to Vector3f(0f, 0f, 0f),
                "light.direction" to Vector3f(-0.8f, -0.6f, 0.1f).normalize(),
                "light.intensity" to 4.6f,
                "background" to Vector3f(0.020f, 0.020f, 0.020f),
                "shadow.strength" to 0.8f,
                "grid.spread" to 1.05f,
                "grid.visible" to 6
            )
        )
        return 4
    }

    /**
     * Run a sweep over [rig], writing a still and a short clip per render.
     *
     * [advance] is how the caller pumps frames, and it is the one thing that differs between a box
     * and a standalone JVM: inside Field the Runloop is already advancing the renderer, so it waits
     * (`renderer.awaitFrames`); here nothing else is driving it, so it calls `renderer.render()`
     * directly. Everything else about the sweep is identical, which is the point of putting it in
     * one function.
     */
    suspend fun sweep(
        rig: Rig,
        renderer: Renderer,
        pool: Path,
        out: Path,
        config: SweepConfig,
        advance: suspend (Int) -> Unit
    ): SweepReport =
        KeyframeSweep(
            rig.scope, pool, out, config,
            onRender = { m ->
                val parents = m.parents.joinToString(", ") { "${it.key} ${"%.2f".format(it.weight)}" }
                val push = m.extrapolationGain?.let { " extrapolated x${"%.2f".format(it)}" } ?: ""
                println("  ${m.id} ${m.status} — $parents$push")
            }
        ).run { render ->
            advance(SETTLE_FRAMES)
            render.captureStill(renderer, rig.bundle.postStack.output(), settleFrames = 0)
            render.captureClip(
                renderer, rig.bundle.postStack.output(),
                frames = CLIP_FRAMES, fps = 12, settleFrames = 0
            ) { _, t ->
                // A small orbit *around* the blended camera position, so the clip moves without
                // overwriting the lane the key set.
                val angle = (t - 0.5) * 0.5
                val at = rig.scope.capture()["camera.at"] as? Vector3f ?: rig.cameraAt
                val x = at.x * cos(angle).toFloat() - at.z * sin(angle).toFloat()
                val z = at.x * sin(angle).toFloat() + at.z * cos(angle).toFloat()
                rig.bundle.scene.camera.at.set(x, at.y, z)
                advance(2)
            }
        }

    // ---- the smoke ----------------------------------------------------------------------------

    fun main(args: Array<String>) {
        val keepOpen = args.contains("--keep")
        Options.enableVR = false
        Options.doVulkanSDK()

        outputDir.mkdirs()
        val window = Window("Field · Keyframe sweep")
        val renderer = Renderer(window)

        // `StandardRenderer.build` allocates targets through a bare `RenderToTexture { … }`, whose
        // renderer defaults to `fromContext()`, so it only works where a Box context can see one.
        // See FabricatePassChainDemo for what the failure looks like without this.
        val hostBox = Box("keyframe-sweep-demo").apply { name = "keyframe-sweep-demo" }
        hostBox.setPropertyRaw("renderer", renderer, doResync = false)
        hostBox.setPropertyRaw("window", window, doResync = false)

        try {
            Box.withBoxContextNow(hostBox) { run(renderer, window) }
            if (keepOpen) {
                println("-- --keep: leaving the window open, close it to exit --")
                while (!window.shouldClose()) pump(renderer, window, 1)
            }
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            t.printStackTrace()
            failures++
        } finally {
            runCatching { renderer.cleanup() }
            runCatching { window.cleanup() }
        }

        if (failures == 0) println("keyframe sweep tests PASSED — archive in ${outputDir.absolutePath}")
        else println("keyframe sweep tests FAILED ($failures)")
        // GLFW and Vulkan leave non-daemon threads behind, so the JVM will not exit on its own.
        exitProcess(if (failures == 0) 0 else 1)
    }

    private fun run(renderer: Renderer, window: Window) {
        val rig = buildRig(renderer)
        rig.bundle.present(renderer = renderer)
        rig.bundle.attachTo(renderer)
        pump(renderer, window, WARMUP_FRAMES)

        val root = outputDir.toPath()
        val pool = root.resolve("pool")
        val out = root.resolve("archive")
        if (out.exists()) out.toFile().deleteRecursively()
        val poolSize = writePool(rig.scope, pool)
        check(poolSize == 4) { "the fixture pool should be four keys" }

        println("sweeping a pool of $poolSize keys:")
        val report = runBlocking {
            // A pump coroutine, so the sweep can wait in *frames* rather than in milliseconds — and
            // so `awaitFrames` is actually exercised here rather than only compiled.
            //
            // Inside Field the Runloop advances the renderer and a callback simply waits. A
            // standalone JVM has no Runloop, so the naive version of this deadlocks: the thread that
            // would render is the thread that is waiting to see a render. `runBlocking`'s dispatcher
            // is single-threaded and cooperative, so putting the pump in a sibling coroutine makes
            // `awaitFrames`'s own `delay`/`yield` hand it the thread — which reproduces the Runloop's
            // interleaving closely enough to test the real waiting path.
            val pump = launch {
                while (isActive) {
                    window.pollEvents()
                    renderer.render()
                    yield()
                }
            }
            try {
                sweep(
                    rig, renderer, pool, out,
                    SweepConfig(
                        seed = 20260916,
                        count = 6,
                        minArity = 2,
                        maxArity = 3,
                        concentration = 0.7,
                        extrapolationProbability = 0.34,
                        // A smoke run must not stop early for a reason the assertions would then
                        // blame on the sweeper, and it is writing tens of kilobytes, not gigabytes.
                        minFreeBytes = 64L shl 20
                    )
                ) { frames -> renderer.awaitFrames(frames) }
            } finally {
                pump.cancelAndJoin()
            }
        }
        println(report)
        expect(report.completed == 6, "six renders completed, not ${report.completed}")
        expect(report.failed == 0, "no render should have failed; ${report.failed} did")

        val rows = readSweepArchive(out)
        expect(rows.size == 6, "six manifests, not ${rows.size}")

        // Every render is complete on disk: the provenance, the key, and the picture.
        rows.forEach { m ->
            val dir = out.resolve("renders/${m.id}")
            expect(dir.resolve("key.json").exists(), "${m.id} has no key.json")
            expect(dir.resolve("manifest.json").exists(), "${m.id} has no manifest.json")
            expect(dir.resolve("still.png").exists(), "${m.id} has no still.png")
            expect(m.status == "ok", "${m.id} ended as ${m.status}")
            expect(
                m.artifacts.any { it.kind == "still" && it.file == "still.png" && it.bytes > 0 },
                "${m.id} did not record its still as an artifact"
            )
            val clip = m.artifacts.firstOrNull { it.kind == "clip" }
            expect(clip != null, "${m.id} recorded no clip artifact at all")
            val clipFile = clip?.file
            if (clipFile != null) {
                expect(dir.resolve(clipFile).exists(), "${m.id} recorded a clip that is not there")
            } else {
                // No ffmpeg on this machine is a stated gap, not a silent one — and the frames that
                // would have been muxed are still on disk for `muxSweepArchiveClips`.
                expect(clip?.note != null, "${m.id} has no clip and no reason")
                expect(dir.resolve("frames").exists(), "${m.id} has neither a clip nor its frames")
                println("  note: ${clip?.note}")
            }
            expect(
                dir.resolve("frames").toFile().listFiles()?.size?.let { it >= CLIP_FRAMES } != false,
                "${m.id} wrote fewer than $CLIP_FRAMES clip frames"
            )
        }

        // The back-fill really happened, on the one key that was written short.
        expect(
            rows.any { m -> m.parents.any { it.key == "older-partial-key" && it.filled.isNotEmpty() } },
            "the partial key should have been back-filled at least once in six renders"
        )
        expect(rows.all { it.droppedLanes.isEmpty() }, "no lane should have been dropped")

        // An Int lane came back as an Int, and the setter that consumes it did not throw.
        val storedVisible = rows.map { m ->
            m to (rig.scope.loadKey(out.resolve("renders/${m.id}/key.json"))["grid.visible"])
        }
        storedVisible.forEach { (m, visible) ->
            expect(visible is Int, "${m.id}: grid.visible is ${visible?.javaClass?.simpleName}, not Int")
        }

        // An extrapolated key can hold an `Int` lane *outside* the target's declared range — 14
        // spheres out of a possible 9 — and that is deliberate on both sides. The sweep rounds to the
        // anchor's type without clamping, because the blend is the arithmetic it claims to be; the
        // clamp lives in `intTarget`, on apply. Which means the key still reproduces the render
        // exactly, and the archive still records the point in the space that was actually visited.
        val outside = storedVisible.firstOrNull { (_, v) -> v is Int && v !in 1..(GRID * GRID) }
        if (outside == null) {
            println("note: no render extrapolated grid.visible out of range this time")
        } else {
            val (m, stored) = outside
            rig.scope.loadKey(out.resolve("renders/${m.id}/key.json"))()
            pump(renderer, window, 2)
            // Read back as a number, not as an `Int`: `intTarget` registers a *float* lane — which is
            // what gives an integer control meaningful interpolation — so `capture()` answers 9.0
            // where the pool's hand-written keys hold 9. Both apply; only the spelling differs.
            val applied = (rig.scope.capture()["grid.visible"] as? Number)?.toInt()
            println("render ${m.id} stored grid.visible=$stored; the target clamped it to $applied on apply")
            expect(
                applied == GRID * GRID,
                "an out-of-range Int lane must clamp to the target's range on apply, not arrive as $applied"
            )
        }

        // ---- the claim that needs the GPU: the picture is a function of the blend ----
        //
        // Not "consecutive renders differ": consecutive renders are independent draws and can
        // legitimately land next to each other in the space — the run that first exercised this had
        // a 0.74/0.26 blend followed by a 0.67/0.30 one, whose pictures *should* be nearly the same,
        // and asserting otherwise failed the sweeper for being correct. What is actually claimed is
        // that pixel distance tracks key distance, which a blend reaching no uniform at all cannot
        // fake: every picture would then be identical and the correlation undefined.
        val stills = rows.map { readLuma(out.resolve("renders/${it.id}/still.png").toFile()) }
        val keys = rows.map { rig.scope.loadKey(out.resolve("renders/${it.id}/key.json")) }
        expect(stills.all { it.isNotEmpty() }, "a still read back as no pixels")
        expect(
            stills.all { luma -> luma.distinct().size > 8 },
            "a still is nearly uniform — the scene did not render"
        )

        val lanes = keys.first().values.keys.filter { lane -> keys.all { it[lane] is Number || it[lane] is Vector3f } }
        val pairs = buildList {
            for (i in rows.indices) for (j in i + 1 until rows.size) {
                add(Triple(keyDistance(keys[i], keys[j], lanes), meanAbsoluteDifference(stills[i], stills[j]), i to j))
            }
        }
        val widest = pairs.maxBy { it.second }
        val correlation = pearson(pairs.map { it.first }, pairs.map { it.second })
        println(
            "across ${pairs.size} pairs: widest picture difference ${"%.2f".format(widest.second)} luma " +
                "(renders ${widest.third.first} and ${widest.third.second}), " +
                "key-distance/pixel-distance correlation ${"%.2f".format(correlation)}"
        )
        expect(
            widest.second > DIFFERENT_PICTURE_FLOOR,
            "no two renders in the archive differ by more than ${widest.second} luma; the night explored nothing"
        )
        expect(
            pairs.all { it.second > 0.0 },
            "two renders produced pixel-identical stills from different keys"
        )
        expect(
            correlation > 0.6,
            "pixel distance does not track key distance (r = $correlation); the blend is not what is being drawn"
        )

        // ---- and the other half: a key is a complete description of a look ----
        val firstKey = rig.scope.loadKey(out.resolve("renders/${rows[0].id}/key.json"))
        firstKey()
        pump(renderer, window, SETTLE_FRAMES * 3)
        val replayed = readbackLuma(renderer, rig)
        val drift = meanAbsoluteDifference(stills[0], replayed)
        println("re-applying render ${rows[0].id}'s key reproduced its still to $drift luma")
        expect(
            drift < SAME_PICTURE_CEILING,
            "re-applying a saved key gave a different picture ($drift luma); a key is not complete"
        )

        println(sweepArchiveSummary(out))
    }

    private fun pump(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
    }

    /**
     * The current frame as luma, through the *same* readback the sweep's own stills came from.
     *
     * That sharing is the point rather than tidiness: if this used a second readback path, a
     * disagreement between the replayed picture and the saved still could be the two paths differing
     * rather than the key being incomplete, and the assertion would mean nothing.
     */
    private fun readbackLuma(renderer: Renderer, rig: Rig): IntArray {
        val bytes = sweepTargetPngBytes(renderer, rig.bundle.postStack.output())
            ?: error("replay readback returned nothing")
        val file = File(outputDir, "replay.png")
        file.writeBytes(bytes)
        return readLuma(file)
    }

    private fun readLuma(file: File): IntArray {
        val image = ImageIO.read(file) ?: return IntArray(0)
        val out = IntArray(image.width * image.height)
        var i = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            out[i++] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return out
    }

    /**
     * Distance between two keys over the lanes that have one, each lane scaled by the spread the
     * *pool* gives it.
     *
     * The scaling is what makes the number mean anything: `light.intensity` ranges over units and
     * `ao.strength` over a fraction of one, so an unscaled distance would be a measurement of the
     * light lane with rounding errors attached.
     */
    private fun keyDistance(a: KeyframeScope.Key, b: KeyframeScope.Key, lanes: List<String>): Double {
        var total = 0.0
        lanes.forEach { lane ->
            val left = a[lane]
            val right = b[lane]
            val delta = when {
                left is Vector3f && right is Vector3f ->
                    (Vector3f(left).sub(right).length() / laneScale(lane)).toDouble()
                left is Number && right is Number ->
                    abs(left.toDouble() - right.toDouble()) / laneScale(lane)
                else -> 0.0
            }
            total += delta * delta
        }
        return kotlin.math.sqrt(total)
    }

    /** A characteristic magnitude per lane, so [keyDistance] compares like with like. */
    private fun laneScale(lane: String): Float = when (lane) {
        "light.intensity" -> 4f
        "grid.visible" -> 8f
        "camera.at", "camera.target" -> 4f
        else -> 1f
    }

    /** Pearson correlation of two equal-length series. Zero when either is constant. */
    private fun pearson(xs: List<Double>, ys: List<Double>): Double {
        val n = min(xs.size, ys.size)
        if (n < 3) return 0.0
        val meanX = xs.take(n).average()
        val meanY = ys.take(n).average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return 0.0
        return sxy / kotlin.math.sqrt(sxx * syy)
    }

    private fun meanAbsoluteDifference(a: IntArray, b: IntArray): Double {
        val n = min(a.size, b.size)
        if (n == 0) return Double.MAX_VALUE
        var total = 0L
        for (i in 0 until n) total += abs(a[i] - b[i]).toLong()
        return total.toDouble() / n
    }

    private fun expect(condition: Boolean, message: String) {
        if (condition) return
        println("FAIL: $message")
        failures++
    }
}
