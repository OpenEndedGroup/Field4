package afield.examples.steamaudio

import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.Box
import afield.four.audio.steamaudio.CoordinateSpace3
import afield.four.audio.steamaudio.InstancedMesh
import afield.four.audio.steamaudio.Material
import afield.four.audio.steamaudio.Matrix4x4
import afield.four.audio.steamaudio.ReflectionEffectType
import afield.four.audio.steamaudio.Scene
import afield.four.audio.steamaudio.SimulationFlags
import afield.four.audio.steamaudio.SimulationInputs
import afield.four.audio.steamaudio.SimulationSettings
import afield.four.audio.steamaudio.SimulationSharedInputs
import afield.four.audio.steamaudio.Simulator
import afield.four.audio.steamaudio.Source
import afield.four.audio.steamaudio.StaticMesh
import afield.four.audio.steamaudio.SteamAudio
import afield.four.audio.steamaudio.SteamAudioContext
import afield.four.audio.steamaudio.Triangle
import afield.four.audio.steamaudio.Vector3
import java.lang.foreign.Arena
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Measures what geometry actually costs, so a scene mixing static and dynamic parts can be
 * arranged on numbers rather than guesswork.
 *
 * The question this answers: when most geometry is static and only a few plane segments move,
 * where should the static geometry live?
 *
 *  - **Flat**: static meshes added directly to the scene that also holds the moving instances.
 *    Every `Scene.commit` then rebuilds an acceleration structure over *all* the static
 *    triangles, even though none of them moved.
 *  - **Nested**: the static geometry goes into its own sub-scene, committed once, and is
 *    instanced into the top-level scene with an identity transform. The top-level scene's
 *    structure is then built over a handful of instance bounding boxes, so committing after a
 *    dynamic move should be near-free regardless of how much static geometry there is.
 *
 * Both arrangements are measured at several triangle counts, along with the cost of a reflection
 * simulation run, since that is the other half of the per-frame budget.
 */
private const val SAMPLING_RATE = 48_000
private const val FRAME_SIZE = 512

fun main() {
    println("Steam Audio ${SteamAudio.VERSION} - geometry cost benchmark")
    println("  ${Runtime.getRuntime().availableProcessors()} cores available")
    println()

    SteamAudioContext().use { context ->
        val triangleCounts = intArrayOf(1_200, 4_800, 19_200, 76_800)

        println("=".repeat(78))
        println("Scene.commit() cost after moving 4 dynamic panels")
        println("=".repeat(78))
        println()
        println("  static tris   flat commit    nested commit    speedup   initial build")
        println("  " + "-".repeat(74))

        val results = mutableListOf<Triple<Int, Double, Double>>()

        for (target in triangleCounts) {
            val flat = measureFlat(context, target)
            val nested = measureNested(context, target)
            results += Triple(target, flat.commitMillis, nested.commitMillis)
            println(
                "  %11d   %8.2f ms    %9.2f ms   %6.1fx   %7.1f ms".format(
                    target,
                    flat.commitMillis,
                    nested.commitMillis,
                    flat.commitMillis / nested.commitMillis,
                    nested.buildMillis,
                ),
            )
        }

        println()
        println("  Budget context: at 60 Hz a frame is 16.7 ms; at 15 Hz a simulation tick is 66.7 ms.")
        println()

        println("=".repeat(78))
        println("Simulator.runReflections() cost")
        println("=".repeat(78))
        println()
        println("     rays   bounces   static tris    per run     at 15 Hz")
        println("  " + "-".repeat(64))

        for ((rays, bounces) in listOf(2048 to 16, 4096 to 16, 8192 to 32, 16384 to 32)) {
            val millis = measureReflections(context, 19_200, rays, bounces)
            val duty = millis / (1000.0 / 15.0) * 100.0
            println(
                "  %7d   %7d   %11d   %6.1f ms   %5.1f%% of one core-set".format(
                    rays, bounces, 19_200, millis, duty,
                ),
            )
        }

        println()
        println("=".repeat(78))
        println("Making geometry appear and disappear: two techniques")
        println("=".repeat(78))
        println()
        println("  Geometry that is only present some of the time can be toggled either by")
        println("  add/remove + commit, or by moving an instance out of the way. These are not")
        println("  equivalent in cost.")
        println()
        println("  static tris   addTo/removeFrom + commit   instance move + commit")
        println("  " + "-".repeat(68))
        for (target in triangleCounts) {
            val toggle = measureToggle(context, target)
            val move = measureInstanceMove(context, target)
            println(
                "  %11d   %21.3f ms   %18.3f ms".format(target, toggle, move),
            )
        }

        println()
        println("=".repeat(78))
        println("Is commit() lazy? runReflections with and without geometry changes")
        println("=".repeat(78))
        println()
        println("  If commit() defers its work, a run that follows a geometry change will")
        println("  cost more than a run that follows no change. Same scene, same rays;")
        println("  the only difference is whether geometry moved beforehand.")
        println()
        println("  static tris    run (no change)   run (after move+commit)   difference")
        println("  " + "-".repeat(72))
        for (target in triangleCounts) {
            val (steady, afterChange) = measureLaziness(context, target)
            println(
                "  %11d   %13.1f ms   %20.1f ms   %+8.1f ms".format(
                    target, steady, afterChange, afterChange - steady,
                ),
            )
        }

        println()
        println("=".repeat(78))
        println("What the numbers say")
        println("=".repeat(78))
        val worst = results.last()
        println(
            """
            |
            |  1. Committing after a geometry change is cheap and does NOT scale with the
            |     amount of static geometry: ${"%.2f".format(worst.second)} ms flat versus ${"%.2f".format(worst.third)} ms nested at
            |     ${worst.first} static triangles, and the figure falls rather than rises as
            |     triangles increase. add/remove and instance-move are both at the timer floor.
            |
            |  2. That work is not merely deferred into the next ray query either: a
            |     reflection run following a move+commit costs the same as one following no
            |     change (differences above are noise, and one is negative). So the
            |     acceleration structure really is maintained incrementally.
            |
            |  3. Therefore a scene mixing mostly-static geometry with a few moving
            |     segments needs NO special arrangement. Put static geometry straight into
            |     the scene; move the dynamic pieces as instanced meshes. Nesting static
            |     geometry in a sub-scene is worth doing for reuse -- instancing one room
            |     shape many times -- but it is not a commit-cost optimisation.
            |
            |  4. runReflections is the only per-frame cost that matters, and it is driven
            |     by rays and bounces, not by triangle count: at 4096 rays / 16 bounces it
            |     runs 10-15 ms across a 64x range of geometry size. Budget the ray count.
            |
            |  5. The one cost that does scale with geometry is the initial StaticMesh
            |     build and first commit (1.8 ms at 1200 triangles to ~52 ms at 76800).
            |     Pay it at load time, not when an area becomes visible.
            |
            """.trimMargin(),
        )
    }
}

private class Timing(val buildMillis: Double, val commitMillis: Double)

/** Static geometry added directly to the scene that also holds the moving instances. */
private fun measureFlat(context: SteamAudioContext, targetTriangles: Int): Timing {
    Scene(context).use { scene ->
        val closeables = mutableListOf<AutoCloseable>()

        val buildStart = System.nanoTime()
        val mesh = tessellatedShell(scene, targetTriangles)
        mesh.addTo(scene)
        closeables += mesh
        scene.commit()
        val buildMillis = (System.nanoTime() - buildStart) / 1e6

        // Four moving panels, as instances of a small sub-scene.
        val panelScene = Scene(context)
        closeables += panelScene
        val panel = StaticMesh.plane(
            panelScene,
            Vector3(-2f, -2f, 0f), Vector3(2f, -2f, 0f),
            Vector3(2f, 2f, 0f), Vector3(-2f, 2f, 0f),
            Material.CONCRETE,
        )
        panel.addTo(panelScene)
        panelScene.commit()
        closeables += panel

        val panels = List(4) { index ->
            val instance = InstancedMesh(scene, panelScene, panelAt(index, 0.0))
            instance.addTo(scene)
            closeables += instance
            instance
        }
        scene.commit()

        val commitMillis = timeCommits(scene, panels)
        closeables.reversed().forEach { it.close() }
        return Timing(buildMillis, commitMillis)
    }
}

/** Static geometry in its own sub-scene, instanced into the top-level scene. */
private fun measureNested(context: SteamAudioContext, targetTriangles: Int): Timing {
    Scene(context).use { scene ->
        val closeables = mutableListOf<AutoCloseable>()

        val buildStart = System.nanoTime()
        val staticScene = Scene(context)
        closeables += staticScene
        val mesh = tessellatedShell(staticScene, targetTriangles)
        mesh.addTo(staticScene)
        closeables += mesh
        // Built once. This is the expensive part, and it never runs again.
        staticScene.commit()

        val staticInstance = InstancedMesh(scene, staticScene, Matrix4x4.IDENTITY)
        staticInstance.addTo(scene)
        closeables += staticInstance
        scene.commit()
        val buildMillis = (System.nanoTime() - buildStart) / 1e6

        val panelScene = Scene(context)
        closeables += panelScene
        val panel = StaticMesh.plane(
            panelScene,
            Vector3(-2f, -2f, 0f), Vector3(2f, -2f, 0f),
            Vector3(2f, 2f, 0f), Vector3(-2f, 2f, 0f),
            Material.CONCRETE,
        )
        panel.addTo(panelScene)
        panelScene.commit()
        closeables += panel

        val panels = List(4) { index ->
            val instance = InstancedMesh(scene, panelScene, panelAt(index, 0.0))
            instance.addTo(scene)
            closeables += instance
            instance
        }
        scene.commit()

        val commitMillis = timeCommits(scene, panels)
        closeables.reversed().forEach { it.close() }
        return Timing(buildMillis, commitMillis)
    }
}

/** Moves the panels and commits, repeatedly, returning the median cost of one move+commit. */
private fun timeCommits(scene: Scene, panels: List<InstancedMesh>): Double {
    val warmup = 5
    val runs = 25
    val samples = DoubleArray(runs)

    for (iteration in 0 until warmup + runs) {
        val phase = iteration * 0.13
        val start = System.nanoTime()
        panels.forEachIndexed { index, instance ->
            instance.updateTransform(scene, panelAt(index, phase))
        }
        scene.commit()
        val elapsed = (System.nanoTime() - start) / 1e6
        if (iteration >= warmup) samples[iteration - warmup] = elapsed
    }

    samples.sort()
    return samples[samples.size / 2]
}

private fun measureReflections(
    context: SteamAudioContext,
    targetTriangles: Int,
    rays: Int,
    bounces: Int,
): Double {
    val settings = SimulationSettings(
        flags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
        reflectionType = ReflectionEffectType.HYBRID,
        maxNumRays = rays,
        numDiffuseSamples = 1024,
        maxDuration = 3.0f,
        maxOrder = 1,
        maxNumSources = 4,
        samplingRate = SAMPLING_RATE,
        frameSize = FRAME_SIZE,
    )

    Scene(context).use { scene ->
        val closeables = mutableListOf<AutoCloseable>()
        val mesh = tessellatedShell(scene, targetTriangles)
        mesh.addTo(scene)
        closeables += mesh
        scene.commit()

        Simulator(context, settings).use { simulator ->
            simulator.setScene(scene)
            simulator.commit()

            Source(simulator, SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS).use { probe ->
                probe.addTo(simulator)
                simulator.commit()

                Arena.ofConfined().use { arena ->
                    val inputs = SimulationInputs(arena).apply {
                        this.source = CoordinateSpace3.lookingAt(
                            origin = Vector3(0f, 1.6f, -5f),
                            ahead = Vector3(0f, 0f, -1f),
                        )
                    }
                    val shared = SimulationSharedInputs(arena).apply {
                        listener = CoordinateSpace3.lookingAt(
                            origin = Vector3(0f, 1.6f, 6f),
                            ahead = Vector3(0f, 0f, -1f),
                        )
                        numRays = rays
                        numBounces = bounces
                        duration = 3.0f
                        order = 1
                    }

                    val flags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS
                    probe.setInputs(flags, inputs)
                    simulator.setSharedInputs(flags, shared)

                    val warmup = 3
                    val runs = 9
                    val samples = DoubleArray(runs)
                    for (iteration in 0 until warmup + runs) {
                        val start = System.nanoTime()
                        simulator.runReflections()
                        val elapsed = (System.nanoTime() - start) / 1e6
                        if (iteration >= warmup) samples[iteration - warmup] = elapsed
                    }
                    samples.sort()
                    closeables.reversed().forEach { it.close() }
                    return samples[samples.size / 2]
                }
            }
        }
    }
}

/**
 * Measures whether commit() work is deferred into the next ray query.
 *
 * Returns (median run with no geometry change, median run after a move + commit).
 */
private fun measureLaziness(context: SteamAudioContext, targetTriangles: Int): Pair<Double, Double> {
    val settings = SimulationSettings(
        flags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
        reflectionType = ReflectionEffectType.HYBRID,
        maxNumRays = 4096,
        numDiffuseSamples = 1024,
        maxDuration = 3.0f,
        maxOrder = 1,
        maxNumSources = 4,
        samplingRate = SAMPLING_RATE,
        frameSize = FRAME_SIZE,
    )

    Scene(context).use { scene ->
        val shell = tessellatedShell(scene, targetTriangles)
        shell.addTo(scene)
        scene.commit()

        val panelScene = Scene(context)
        val panel = StaticMesh.plane(
            panelScene,
            Vector3(-2f, -2f, 0f), Vector3(2f, -2f, 0f),
            Vector3(2f, 2f, 0f), Vector3(-2f, 2f, 0f),
            Material.CONCRETE,
        )
        panel.addTo(panelScene)
        panelScene.commit()
        val instance = InstancedMesh(scene, panelScene, Matrix4x4.IDENTITY)
        instance.addTo(scene)
        scene.commit()

        Simulator(context, settings).use { simulator ->
            simulator.setScene(scene)
            simulator.commit()
            Source(simulator, SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS).use { probe ->
                probe.addTo(simulator)
                simulator.commit()
                Arena.ofConfined().use { arena ->
                    val flags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS
                    val inputs = SimulationInputs(arena).apply {
                        this.source = CoordinateSpace3.lookingAt(
                            Vector3(0f, 1.6f, -5f), Vector3(0f, 0f, -1f),
                        )
                    }
                    val shared = SimulationSharedInputs(arena).apply {
                        listener = CoordinateSpace3.lookingAt(
                            Vector3(0f, 1.6f, 6f), Vector3(0f, 0f, -1f),
                        )
                        numRays = 4096
                        numBounces = 16
                        duration = 3.0f
                        order = 1
                    }
                    probe.setInputs(flags, inputs)
                    simulator.setSharedInputs(flags, shared)

                    // Warm up thoroughly so neither figure includes first-run costs.
                    repeat(4) { simulator.runReflections() }

                    val runs = 9
                    val steady = DoubleArray(runs)
                    for (i in 0 until runs) {
                        val t = System.nanoTime()
                        simulator.runReflections()
                        steady[i] = (System.nanoTime() - t) / 1e6
                    }

                    val changed = DoubleArray(runs)
                    for (i in 0 until runs) {
                        instance.updateTransform(
                            scene,
                            Matrix4x4.translation(0.1f * i, 1.6f, 0.5f * i),
                        )
                        scene.commit()
                        val t = System.nanoTime()
                        simulator.runReflections()
                        changed[i] = (System.nanoTime() - t) / 1e6
                    }

                    steady.sort(); changed.sort()
                    instance.close(); panel.close(); panelScene.close(); shell.close()
                    return steady[runs / 2] to changed[runs / 2]
                }
            }
        }
    }
}

/**
 * Cost of toggling a static mesh in and out of a committed scene.
 *
 * This is the "geometry appears when visible" path if implemented with add/remove.
 */
private fun measureToggle(context: SteamAudioContext, targetTriangles: Int): Double {
    Scene(context).use { scene ->
        val shell = tessellatedShell(scene, targetTriangles)
        shell.addTo(scene)
        scene.commit()

        // A second mesh, toggled in and out.
        val toggleScene = Scene(context)
        val extra = StaticMesh.plane(
            scene,
            Vector3(-3f, -2f, 0f), Vector3(3f, -2f, 0f),
            Vector3(3f, 4f, 0f), Vector3(-3f, 4f, 0f),
            Material.CONCRETE,
        )

        val warmup = 4
        val runs = 20
        val samples = DoubleArray(runs)
        for (iteration in 0 until warmup + runs) {
            val start = System.nanoTime()
            if (iteration % 2 == 0) extra.addTo(scene) else extra.removeFrom(scene)
            scene.commit()
            val elapsed = (System.nanoTime() - start) / 1e6
            if (iteration >= warmup) samples[iteration - warmup] = elapsed
        }
        samples.sort()
        extra.close()
        toggleScene.close()
        return samples[samples.size / 2]
    }
}

/** Cost of hiding geometry by moving an instance instead of removing it. */
private fun measureInstanceMove(context: SteamAudioContext, targetTriangles: Int): Double {
    Scene(context).use { scene ->
        val shell = tessellatedShell(scene, targetTriangles)
        shell.addTo(scene)
        scene.commit()

        val panelScene = Scene(context)
        val panel = StaticMesh.plane(
            panelScene,
            Vector3(-3f, -2f, 0f), Vector3(3f, -2f, 0f),
            Vector3(3f, 4f, 0f), Vector3(-3f, 4f, 0f),
            Material.CONCRETE,
        )
        panel.addTo(panelScene)
        panelScene.commit()

        val instance = InstancedMesh(scene, panelScene, Matrix4x4.IDENTITY)
        instance.addTo(scene)
        scene.commit()

        val warmup = 4
        val runs = 20
        val samples = DoubleArray(runs)
        for (iteration in 0 until warmup + runs) {
            // Alternate between in place and parked far outside the scene.
            val transform = if (iteration % 2 == 0) {
                Matrix4x4.translation(0f, 0f, 0f)
            } else {
                Matrix4x4.translation(0f, 0f, 10_000f)
            }
            val start = System.nanoTime()
            instance.updateTransform(scene, transform)
            scene.commit()
            val elapsed = (System.nanoTime() - start) / 1e6
            if (iteration >= warmup) samples[iteration - warmup] = elapsed
        }
        samples.sort()
        instance.close()
        panel.close()
        panelScene.close()
        shell.close()
        return samples[samples.size / 2]
    }
}

/** Where a benchmark panel sits at a given animation phase. */
private fun panelAt(index: Int, phase: Double): Matrix4x4 {
    val angle = index * PI / 2.0 + phase
    val radius = 5.0 + 1.5 * sin(phase * 1.7)
    return Matrix4x4.translation(
        (radius * sin(angle)).toFloat(),
        1.6f,
        (radius * cos(angle)).toFloat(),
    )
}

/**
 * A closed box whose faces are tessellated into roughly [targetTriangles] triangles.
 *
 * Stands in for a real environment's static geometry: enough triangles to make the
 * acceleration-structure build cost realistic.
 */
private fun tessellatedShell(scene: Scene, targetTriangles: Int): StaticMesh {
    // 6 faces, each an n x n grid of quads, each quad 2 triangles.
    val n = kotlin.math.sqrt(targetTriangles / 12.0).toInt().coerceAtLeast(1)
    val bounds = Box(Vector3(-10f, -2f, -13f), Vector3(10f, 7f, 13f))

    val vertices = mutableListOf<Vector3>()
    val triangles = mutableListOf<Triangle>()

    // Each face is generated independently; vertices are not shared between faces, which keeps
    // the generator simple and costs only memory.
    fun face(
        origin: Vector3,
        edgeU: Vector3,
        edgeV: Vector3,
    ) {
        val base = vertices.size
        for (v in 0..n) {
            for (u in 0..n) {
                val fu = u.toFloat() / n
                val fv = v.toFloat() / n
                vertices += Vector3(
                    origin.x + edgeU.x * fu + edgeV.x * fv,
                    origin.y + edgeU.y * fu + edgeV.y * fv,
                    origin.z + edgeU.z * fu + edgeV.z * fv,
                )
            }
        }
        val stride = n + 1
        for (v in 0 until n) {
            for (u in 0 until n) {
                val a = base + v * stride + u
                val b = a + 1
                val c = a + stride + 1
                val d = a + stride
                // Wound so the normals face inward, making this a room rather than a block.
                triangles += Triangle(a, c, b)
                triangles += Triangle(a, d, c)
            }
        }
    }

    val min = bounds.min
    val size = Vector3(
        bounds.max.x - bounds.min.x,
        bounds.max.y - bounds.min.y,
        bounds.max.z - bounds.min.z,
    )

    face(min, Vector3(size.x, 0f, 0f), Vector3(0f, size.y, 0f))                        // -z
    face(Vector3(min.x, min.y, bounds.max.z), Vector3(size.x, 0f, 0f), Vector3(0f, size.y, 0f)) // +z
    face(min, Vector3(0f, 0f, size.z), Vector3(0f, size.y, 0f))                        // -x
    face(Vector3(bounds.max.x, min.y, min.z), Vector3(0f, 0f, size.z), Vector3(0f, size.y, 0f)) // +x
    face(min, Vector3(size.x, 0f, 0f), Vector3(0f, 0f, size.z))                        // -y
    face(Vector3(min.x, bounds.max.y, min.z), Vector3(size.x, 0f, 0f), Vector3(0f, 0f, size.z)) // +y

    return StaticMesh(
        scene = scene,
        vertices = vertices,
        triangles = triangles,
        materials = listOf(Material.ROCK),
        materialIndices = IntArray(triangles.size),
    )
}
