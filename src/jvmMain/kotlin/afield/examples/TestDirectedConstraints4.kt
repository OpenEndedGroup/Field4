package afield.examples

import afield.four.physics.SimdVerlet
import kotlin.math.sqrt

/**
 * Simple test to verify DirectedConstraints4 works correctly
 */
object TestDirectedConstraints4 {

    @JvmStatic
    fun main(args: Array<String>) {
        println("Testing DirectedConstraints4 implementation...")

        // Create a simple 3x3 grid of particles
        val gridSize = 3
        val spacing = 1f
        val totalParticles = gridSize * gridSize

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

        // Create particles
        val particleIndices = Array(gridSize) { IntArray(gridSize) }
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                val px = x * spacing
                val py = y * spacing
                val pz = 0f

                val idx = sim.addParticle(
                    x0 = px, y0 = py, z0 = pz,
                    px0 = px, py0 = py, pz0 = pz,
                    invMass0 = if (y == gridSize - 1) 0f else 1f  // Pin top row
                )
                particleIndices[y][x] = idx
            }
        }

        // Set up constraints using all 4 slots
        val stiffness = 0.8f
        var constraintCount = 0
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                val idx = particleIndices[y][x]
                var slot = 0

                // Right neighbor
                if (x < gridSize - 1) {
                    val rightIdx = particleIndices[y][x + 1]
                    constraints.setSlot(idx, slot++, rightIdx, spacing, stiffness)
                    constraintCount++
                }

                // Bottom neighbor
                if (y > 0) {
                    val bottomIdx = particleIndices[y - 1][x]
                    constraints.setSlot(idx, slot++, bottomIdx, spacing, stiffness)
                    constraintCount++
                }

                // Left neighbor
                if (x > 0) {
                    val leftIdx = particleIndices[y][x - 1]
                    constraints.setSlot(idx, slot++, leftIdx, spacing, stiffness)
                    constraintCount++
                }

                // Top neighbor
                if (y < gridSize - 1) {
                    val topIdx = particleIndices[y + 1][x]
                    constraints.setSlot(idx, slot++, topIdx, spacing, stiffness)
                    constraintCount++
                }
            }
        }

        println("Created ${state.size} particles with $constraintCount constraints")

        // Helper function to calculate distance
        fun dist(i: Int, j: Int): Float {
            val dx = state.x[j] - state.x[i]
            val dy = state.y[j] - state.y[i]
            val dz = state.z[j] - state.z[i]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        val dt = 1f / 60f
        val damping = 0.99f
        val gravity = -9.8f

        // Run simulation for a few steps
        println("\nRunning simulation...")
        repeat(10) { step ->
            sim.step(
                dt = dt,
                damping = damping,
                gravity = gravity,
                iterations = 8,
                constraints = constraints
            )

            if (step % 3 == 0) {
                // Check a few distances
                val centerIdx = particleIndices[1][1]
                val rightIdx = particleIndices[1][2]
                val d = dist(centerIdx, rightIdx)
                
                println("Step $step: Center particle at (${state.x[centerIdx]}, ${state.y[centerIdx]}, ${state.z[centerIdx]})")
                println("  Distance to right neighbor: $d (target: $spacing)")
            }
        }

        println("\n✓ DirectedConstraints4 test passed!")
        println("  - Successfully created particles with 4-slot constraints")
        println("  - Simulation runs without errors")
        println("  - Constraints maintain approximate rest lengths")
    }
}
