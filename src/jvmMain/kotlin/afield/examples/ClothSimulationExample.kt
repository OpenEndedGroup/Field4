package afield.examples

import afield.four.physics.SimdVerlet
import afield.generator2
import afield.graphics.FLine
import afield.stdlib.vec
import afield.stdlib.linalg.minus
import afield.stdlib.linalg.plus
import kotlin.math.sqrt

/**
 * Interactive cloth simulation using SimdVerlet with 4-slot distance constraints.
 *
 * Features:
 * - Cloth represented as a grid of particles connected by distance constraints
 * - Visualized as a sequence of FLines forming a wireframe mesh
 * - Optional curvature force to flatten the cloth
 * - Can be integrated into a rendering system that accepts List<FLine>
 */
object ClothSimulationExample {
    @Volatile
    private var activeSimulation: InteractiveClothSimulation? = null

    /**
     * Interactive cloth simulation handle with externally callable grab controls.
     */
    class InteractiveClothSimulation internal constructor(
        val frames: Sequence<List<FLine>>,
        private val grabNearestFn: (Float, Float, Float) -> Boolean,
        private val moveGrabbedFn: (Float, Float, Float) -> Boolean,
        private val releaseGrabbedFn: () -> Boolean
    ) {
        fun grabNearest(worldX: Float, worldY: Float, worldZ: Float): Boolean = grabNearestFn(worldX, worldY, worldZ)
        fun grabNearest(worldX: Float, worldY: Float): Boolean = grabNearest(worldX, worldY, 0f)

        fun moveGrabbed(worldX: Float, worldY: Float, worldZ: Float): Boolean = moveGrabbedFn(worldX, worldY, worldZ)
        fun moveGrabbed(worldX: Float, worldY: Float): Boolean = moveGrabbed(worldX, worldY, 0f)

        fun releaseGrabbed(): Boolean = releaseGrabbedFn()
    }

    /**
     * Convenience passthrough for external callers that do not hold the simulation handle.
     * Routes to the most recently created interactive simulation.
     */
    fun grabNearest(worldX: Float, worldY: Float, worldZ: Float): Boolean =
        activeSimulation?.grabNearest(worldX, worldY, worldZ) ?: false

    fun grabNearest(worldX: Float, worldY: Float): Boolean =
        grabNearest(worldX, worldY, 0f)

    fun moveGrabbed(worldX: Float, worldY: Float, worldZ: Float): Boolean =
        activeSimulation?.moveGrabbed(worldX, worldY, worldZ) ?: false

    fun moveGrabbed(worldX: Float, worldY: Float): Boolean =
        moveGrabbed(worldX, worldY, 0f)

    fun releaseGrabbed(): Boolean =
        activeSimulation?.releaseGrabbed() ?: false

    /**
     * Backward-compatible API that returns only the frame sequence.
     */
    fun createClothSimulation(
        clothWidth: Int = 20,
        clothHeight: Int = 20,
        spacing: Float = 0.5f,
        enableCurvatureForce: Boolean = true,
        curvatureStrength: Float = 0.05f
    ): Sequence<List<FLine>> =
        createInteractiveClothSimulation(
            clothWidth = clothWidth,
            clothHeight = clothHeight,
            spacing = spacing,
            enableCurvatureForce = enableCurvatureForce,
            curvatureStrength = curvatureStrength
        ).frames

    /**
     * Create and run a cloth simulation with externally callable interaction controls.
     */
    fun createInteractiveClothSimulation(
        clothWidth: Int = 20,
        clothHeight: Int = 20,
        spacing: Float = 0.5f,
        enableCurvatureForce: Boolean = true,
        curvatureStrength: Float = 0.05f
    ): InteractiveClothSimulation {
        // Initialize physics state
        val totalParticles = clothWidth * clothHeight
        val state = SimdVerlet.State3(
            x = FloatArray(0),
            y = FloatArray(0),
            z = FloatArray(0),
            px = FloatArray(0),
            py = FloatArray(0),
            pz = FloatArray(0),
            ax = FloatArray(0),
            ay = FloatArray(0),
            az = FloatArray(0),
            invMass = FloatArray(0)
        )

        // Create DirectedConstraints4 for cloth with up to 4 neighbors (up, down, left, right)
        val constraints = SimdVerlet.DirectedConstraints4(
            n0 = IntArray(totalParticles) { -1 },
            n1 = IntArray(totalParticles) { -1 },
            n2 = IntArray(totalParticles) { -1 },
            n3 = IntArray(totalParticles) { -1 },
            r0 = FloatArray(totalParticles),
            r1 = FloatArray(totalParticles),
            r2 = FloatArray(totalParticles),
            r3 = FloatArray(totalParticles),
            s0 = FloatArray(totalParticles),
            s1 = FloatArray(totalParticles),
            s2 = FloatArray(totalParticles),
            s3 = FloatArray(totalParticles)
        )

        val sim = SimdVerlet(state, null)

        // Create cloth particles in a grid
        val particleIndices = Array(clothHeight) { IntArray(clothWidth) }
        for (y in 0 until clothHeight) {
            for (x in 0 until clothWidth) {
                val px = x * spacing
                val py = y * spacing
                val pz = 0f

                val idx = sim.addParticle(
                    x0 = px, y0 = py, z0 = pz,
                    px0 = px, py0 = py, pz0 = pz,
                    invMass0 = if (y == clothHeight - 1) 0f else 1f  // Pin top row
                )
                particleIndices[y][x] = idx
            }
        }

        // Set up distance constraints (structural springs)
        val stiffness = 0.8f
        for (y in 0 until clothHeight) {
            for (x in 0 until clothWidth) {
                val idx = particleIndices[y][x]
                var slot = 0

                // Connect to right neighbor
                if (x < clothWidth - 1) {
                    val rightIdx = particleIndices[y][x + 1]
                    constraints.setSlot(idx, slot++, rightIdx, spacing, stiffness)
                }

                // Connect to bottom neighbor
                if (y > 0) {
                    val bottomIdx = particleIndices[y - 1][x]
                    constraints.setSlot(idx, slot++, bottomIdx, spacing, stiffness)
                }

                // Connect to left neighbor
                if (x > 0) {
                    val leftIdx = particleIndices[y][x - 1]
                    constraints.setSlot(idx, slot++, leftIdx, spacing, stiffness)
                }

                // Connect to top neighbor
                if (y < clothHeight - 1) {
                    val topIdx = particleIndices[y + 1][x]
                    constraints.setSlot(idx, slot++, topIdx, spacing, stiffness)
                }
            }
        }

        // Add curvature force to flatten cloth (optional)
        if (enableCurvatureForce) {
            for (y in 1 until clothHeight - 1) {
                for (x in 1 until clothWidth - 1) {
                    val idx = particleIndices[y][x]

                    sim.addForceGenerator(idx, "curvature", generator2 {
                        while (true) {
                            val ctx = yield()
                            val i = ctx.index

                            // Get neighboring particle indices
                            val left = particleIndices[y][x - 1]
                            val right = particleIndices[y][x + 1]
                            val top = particleIndices[y + 1][x]
                            val bottom = particleIndices[y - 1][x]

                            // Calculate average of neighbors
                            val avgX = (ctx.state.x[left] + ctx.state.x[right] +
                                       ctx.state.x[top] + ctx.state.x[bottom]) / 4f
                            val avgY = (ctx.state.y[left] + ctx.state.y[right] +
                                       ctx.state.y[top] + ctx.state.y[bottom]) / 4f
                            val avgZ = (ctx.state.z[left] + ctx.state.z[right] +
                                       ctx.state.z[top] + ctx.state.z[bottom]) / 4f

                            // Apply force toward average (flattening force)
                            val dx = avgX - ctx.state.x[i]
                            val dy = avgY - ctx.state.y[i]
                            val dz = avgZ - ctx.state.z[i]

                            ctx.state.ax[i] += dx * curvatureStrength
                            ctx.state.ay[i] += dy * curvatureStrength
                            ctx.state.az[i] += dz * curvatureStrength
                        }
                    })
                }
            }
        }

        // Add a force generator to grab and move a particle (example for interactive use)
        val simulationLock = Any()
        var grabbedParticleIndex: Int? = null
        var targetX = 0f
        var targetY = 0f
        var targetZ = 0f

        fun grabNearest3D(worldX: Float, worldY: Float, worldZ: Float): Boolean = synchronized(simulationLock) {
            var nearestIdx = -1
            var nearestDist = Float.POSITIVE_INFINITY

            for (i in 0 until state.size) {
                val dx = state.x[i] - worldX
                val dy = state.y[i] - worldY
                val dz = state.z[i] - worldZ
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist < nearestDist) {
                    nearestDist = dist
                    nearestIdx = i
                }
            }

            if (nearestIdx < 0 || nearestDist >= spacing * 2) {
                return@synchronized false
            }

            grabbedParticleIndex?.let { existingIdx ->
                state.forceGenerators[existingIdx].remove("grabbed")
            }

            grabbedParticleIndex = nearestIdx
            targetX = state.x[nearestIdx]
            targetY = state.y[nearestIdx]
            targetZ = state.z[nearestIdx]

            // Add position controller for grabbed particle
            sim.addPidPositionController(
                nearestIdx,
                "grabbed",
                targetX = { targetX },
                targetY = { targetY },
                targetZ = { targetZ },
                kp = 100f,
                ki = 0f,
                kd = 10f,
                maxForce = 1000f
            )
            true
        }

        fun moveGrabbed3D(worldX: Float, worldY: Float, worldZ: Float): Boolean = synchronized(simulationLock) {
            if (grabbedParticleIndex == null) {
                return@synchronized false
            }
            targetX = worldX
            targetY = worldY
            targetZ = worldZ
            true
        }

        fun releaseGrabbed(): Boolean = synchronized(simulationLock) {
            val idx = grabbedParticleIndex ?: return@synchronized false
            state.forceGenerators[idx].remove("grabbed")
            grabbedParticleIndex = null
            true
        }

        // Simulation parameters
        val dt = 1f / 60f
        val damping = 0.99f
        val gravity = 0.0f

        val frames = sequence {
            // Main simulation loop - yields FLines for each frame
            while (true) {
                val lines = synchronized(simulationLock) {
                    // Step physics simulation with 4-slot constraints
                    sim.step(
                        dt = dt,
                        damping = damping,
                        gravity = gravity,
                        iterations = 8,
                        constraints = constraints
                    )

                    // Generate FLines for cloth wireframe
                    val frameLines = mutableListOf<FLine>()

                    // Horizontal lines
                    for (y in 0 until clothHeight) {
                        val line = FLine()
                        for (x in 0 until clothWidth) {
                            val idx = particleIndices[y][x]
                            val pos = vec(state.x[idx], state.y[idx], state.z[idx])
                            if (x == 0) {
                                line.moveTo(pos)
                            } else {
                                line.lineTo(pos)
                            }
                        }
                        frameLines.add(line)
                    }

                    // Vertical lines
                    for (x in 0 until clothWidth) {
                        val line = FLine()
                        for (y in 0 until clothHeight) {
                            val idx = particleIndices[y][x]
                            val pos = vec(state.x[idx], state.y[idx], state.z[idx])
                            if (y == 0) {
                                line.moveTo(pos)
                            } else {
                                line.lineTo(pos)
                            }
                        }
                        frameLines.add(line)
                    }

                    // Highlight grabbed particle if any
                    grabbedParticleIndex?.let { idx ->
                        val line = FLine()
                        val pos = vec(state.x[idx], state.y[idx], state.z[idx])
                        line.moveTo(pos - vec(0.2f, 0f, 0f))
                        line.lineTo(pos + vec(0.2f, 0f, 0f))
                        line.moveTo(pos - vec(0f, 0.2f, 0f))
                        line.lineTo(pos + vec(0f, 0.2f, 0f))
                        line.properties["color"] = vec(1, 0, 0, 1) // Red color
                        line.properties["pointSize"] = 8f
                        frameLines.add(line)
                    }

                    frameLines
                }

                yield(lines)
            }
        }

        val interactiveSimulation = InteractiveClothSimulation(
            frames = frames,
            grabNearestFn = ::grabNearest3D,
            moveGrabbedFn = ::moveGrabbed3D,
            releaseGrabbedFn = ::releaseGrabbed
        )
        activeSimulation = interactiveSimulation
        return interactiveSimulation
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Cloth Simulation Example - demonstrating DirectedConstraints4")
        println("Running simulation for 100 frames...")

        val simulation = createClothSimulation(
            clothWidth = 15,
            clothHeight = 15,
            spacing = 0.5f,
            enableCurvatureForce = true,
            curvatureStrength = 0.05f
        )

        val iterator = simulation.iterator()

        // Simulate a few frames and print statistics
        repeat(100) { frame ->
            val lines = iterator.next()

            if (frame % 10 == 0) {
                println("Frame $frame: Generated ${lines.size} FLines")

                // Print some sample positions from the first line
                if (lines.isNotEmpty()) {
                    val firstLine = lines[0]
                    println("  First line has ${firstLine.nodes.size} nodes")
                }
            }
        }

        println("Simulation complete!")
    }
}
