package afield.examples

import afield.four.physics.Cloth
import afield.four.physics.SimdVerlet
import afield.four.physics.addCloth
import afield.graphics.FLine
import afield.stdlib.vec

/**
 * Demo of [Cloth] — the reusable grid-cloth builder over `SimdVerlet`'s 4-slot distance
 * constraints. All of the physics lives in [Cloth] now; what is left here is the part that is
 * genuinely example-shaped: standing up a simulation, wrapping the frame loop so the grabbed
 * particle gets a red crosshair, and the process-level "most recent simulation" handle that
 * lets an interactive box call [grabNearest] without holding one.
 */
object ClothSimulationExample {
    @Volatile
    private var activeSimulation: InteractiveClothSimulation? = null

    /**
     * Interactive cloth simulation handle with externally callable grab controls.
     */
    class InteractiveClothSimulation internal constructor(
        val cloth: Cloth,
        val frames: Sequence<List<FLine>>
    ) {
        val sim: SimdVerlet get() = cloth.sim

        fun grabNearest(worldX: Float, worldY: Float, worldZ: Float): Boolean =
            cloth.grabNearest(worldX, worldY, worldZ) >= 0

        fun grabNearest(worldX: Float, worldY: Float): Boolean = grabNearest(worldX, worldY, 0f)

        fun moveGrabbed(worldX: Float, worldY: Float, worldZ: Float): Boolean =
            cloth.moveGrabbed(worldX, worldY, worldZ)

        fun moveGrabbed(worldX: Float, worldY: Float): Boolean = moveGrabbed(worldX, worldY, 0f)

        fun releaseGrabbed(): Boolean = cloth.releaseGrabbed()
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
        val sim = SimdVerlet(SimdVerlet.State3.empty())

        val cloth = sim.addCloth(
            width = clothWidth,
            height = clothHeight,
            spacing = spacing,
            stiffness = 0.8f,
            curvatureStrength = if (enableCurvatureForce) curvatureStrength else 0f
        )

        val frames = sequence {
            while (true) {
                cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 0f, iterations = 8)
                yield(cloth.lines() + grabHighlight(cloth))
            }
        }

        val interactiveSimulation = InteractiveClothSimulation(cloth, frames)
        activeSimulation = interactiveSimulation
        return interactiveSimulation
    }

    /** A red cross on the grabbed particle, or nothing. */
    private fun grabHighlight(cloth: Cloth): List<FLine> {
        val index = cloth.grabbedIndex
        if (index < 0) return emptyList()

        val state = cloth.sim.state
        val pos = vec(state.x[index], state.y[index], state.z[index])
        val line = FLine()
        line.moveTo(pos.x - 0.2f, pos.y, pos.z)
        line.lineTo(pos.x + 0.2f, pos.y, pos.z)
        line.moveTo(pos.x, pos.y - 0.2f, pos.z)
        line.lineTo(pos.x, pos.y + 0.2f, pos.z)
        line.properties["color"] = vec(1, 0, 0, 1)
        line.properties["pointSize"] = 8f
        return listOf(line)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Cloth Simulation Example - demonstrating afield.four.physics.Cloth")
        println("Running simulation for 100 frames...")

        val simulation = createInteractiveClothSimulation(
            clothWidth = 15,
            clothHeight = 15,
            spacing = 0.5f,
            enableCurvatureForce = true,
            curvatureStrength = 0.05f
        )
        val cloth = simulation.cloth
        val iterator = simulation.frames.iterator()

        // Grab a corner and drag it, so the PID controller and the constraint solve both get
        // exercised rather than just the initial rest state.
        val grabbed = cloth.grabNearest(cloth.position(0, 0).x, cloth.position(0, 0).y, 0f)
        println("Grabbed particle $grabbed of ${cloth.count}")

        repeat(100) { frame ->
            val lines = iterator.next()
            cloth.moveGrabbed(-1f + frame * 0.01f, -1f, 0.5f)

            if (frame % 10 == 0) {
                val corner = cloth.position(0, 0)
                println("Frame $frame: ${lines.size} FLines, corner at $corner")
            }
        }

        cloth.releaseGrabbed()
        println("Simulation complete!")
    }
}
