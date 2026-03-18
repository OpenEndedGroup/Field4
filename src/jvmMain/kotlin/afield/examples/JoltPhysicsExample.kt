package afield.examples

import afield.four.physics.JoltBodyDef3D
import afield.four.physics.JoltBodyPick3D
import afield.four.physics.JoltPhysicsWorld3D
import afield.four.physics.JoltShape3D
import afield.graphics.FLine
import afield.stdlib.Quat
import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class JoltPickupLoopRuntime internal constructor(
	val frames: Sequence<List<FLine>>,
	private val grabFn: (queryPoint: Vec3, controllerPosition: Vec3, controllerRotation: Quat, maxOutsideDistance: Double) -> JoltBodyPick3D?,
	private val continueFn: (controllerPosition: Vec3, controllerRotation: Quat) -> Boolean,
	private val releaseFn: () -> String?,
	private val grabbedBodyNameFn: () -> String?,
	private val closeFn: () -> Unit
) : AutoCloseable {
	fun grab(
		queryPoint: Vec3,
		controllerPosition: Vec3 = Vec3(queryPoint),
		controllerRotation: Quat = Quat(),
		maxOutsideDistance: Double = 0.08
	): JoltBodyPick3D? = grabFn(queryPoint, controllerPosition, controllerRotation, maxOutsideDistance)

	fun grab(
		controllerPosition: Vec3,
		controllerRotation: Quat,
		maxOutsideDistance: Double = 0.08
	): JoltBodyPick3D? = grab(
		queryPoint = controllerPosition,
		controllerPosition = controllerPosition,
		controllerRotation = controllerRotation,
		maxOutsideDistance = maxOutsideDistance
	)

	fun continueGrab(controllerPosition: Vec3, controllerRotation: Quat): Boolean =
		continueFn(controllerPosition, controllerRotation)

	fun `continue`(controllerPosition: Vec3, controllerRotation: Quat): Boolean =
		continueGrab(controllerPosition, controllerRotation)

	fun release(): String? = releaseFn()

	fun grabbedBodyName(): String? = grabbedBodyNameFn()

	override fun close() {
		closeFn()
	}
}

object JoltPhysicsExample {
	@JvmStatic
	fun main(args: Array<String>) {
		if (args.contains("--off-center")) {
			runOffCenterCollisionDemo()
		} else {
			runStressTestChaos()
		}
	}

	fun runStressTestChaos(seed: Int = 1337, bodyCount: Int = 20) = sequence {
		val random = Random(seed)
		val planeA = PlaneDef(nx = 0.24, ny = 0.97, nz = 0.10, constant = -0.90)
		val planeB = PlaneDef(nx = -0.18, ny = 0.98, nz = -0.21, constant = -1.70)

		JoltPhysicsWorld3D().use { engine ->
			engine.createBody(
				JoltBodyDef3D(
					name = "planeA",
					shape = JoltShape3D.Plane(planeA.nx, planeA.ny, planeA.nz, planeA.constant),
					x = 0.0,
					y = 0.0,
					z = 0.0,
					dynamic = false,
					friction = 0.5,
					restitution = 0.8
				)
			)
			engine.createBody(
				JoltBodyDef3D(
					name = "planeB",
					shape = JoltShape3D.Plane(planeB.nx, planeB.ny, planeB.nz, planeB.constant),
					x = 0.0,
					y = 0.0,
					z = 0.0,
					dynamic = false,
					friction = 0.0,
					restitution = 0.3
				)
			)

			val spawns = generateNonOverlappingCuboidSpawns(
				count = bodyCount,
				random = random,
				planeA = planeA,
				planeB = planeB
			)
			check(!hasAnySpawnOverlap(spawns)) { "Internal error: overlapping spawn set." }
			println("spawned ${spawns.size} cuboids (seed=$seed), initial overlap check: PASS")

			spawns.forEachIndexed { index, spawn ->
				engine.createBody(
					JoltBodyDef3D(
						name = "box_${index.toString().padStart(2, '0')}",
						shape = JoltShape3D.Cuboid(spawn.width, spawn.height, spawn.depth),
						x = spawn.x,
						y = spawn.y,
						z = spawn.z,
						friction = random.nextDouble(0.05, 0.06),
						restitution = random.nextDouble(0.02, 0.90),
						linearDamping = random.nextDouble(0.0, 0.03),
						angularDamping = random.nextDouble(0.0, 0.03),
						vx = random.nextDouble(-0.9, 0.9),
						vy = 0.0,
						vz = random.nextDouble(-0.9, 0.9),
						wx = random.nextDouble(-0.6, 0.6),
						wy = random.nextDouble(-0.6, 0.6),
						wz = random.nextDouble(-0.6, 0.6)
					)
				)
			}

			val dt = 1.0 / 120.0
			val steps = 120 * 10
			repeat(steps) { i ->
				val states = engine.step(dt)
				yield(engine.debugWireLines3D(axisLength = 0.18f))
				if (i % 12 == 0) {
					val t = i * dt
					val boxes = states.filter { it.name.startsWith("box_") }
					val avgHeight = boxes.map { it.position.y.toDouble() }.average()
					val avgSpin = boxes.map { sqrt(it.angularVelocity.lengthSquared().toDouble()) }.average()
					val topSpin = boxes.sortedByDescending { it.angularVelocity.lengthSquared() }.take(8)

					println("t=${"%.2f".format(t)} avgY=${avgHeight.format3()} avgSpin=${avgSpin.format3()}")
					for (state in topSpin) {
						println(
							"  ${state.name}: " +
								"p=(${state.position.x.format3()}, ${state.position.y.format3()}, ${state.position.z.format3()}) " +
								"q=(${state.rotation.x.format3()}, ${state.rotation.y.format3()}, ${state.rotation.z.format3()}, ${state.rotation.w.format3()}) " +
								"w=(${state.angularVelocity.x.format3()}, ${state.angularVelocity.y.format3()}, ${state.angularVelocity.z.format3()})"
						)
					}
				}
			}

			val debugLines = engine.debugWireLines3D(axisLength = 0.18f)
			println("debugWireLines3D produced ${debugLines.size} lines")
		}
	}

	fun runStressTestPickupLoop(
		seed: Int = 1337,
		bodyCount: Int = 20,
		dt: Double = 1.0 / 120.0,
		floorPlaneHeight: Float = 0f
	): JoltPickupLoopRuntime {
		require(bodyCount > 0) { "bodyCount must be > 0, got $bodyCount" }
		require(dt > 0.0) { "dt must be > 0, got $dt" }

		val random = Random(seed)
		val ground = PlaneDef(nx = 0.0, ny = 1.0, nz = 0.0, constant = -floorPlaneHeight.toDouble())
		val engine = JoltPhysicsWorld3D()
		val simulationLock = Any()
		var closed = false

		synchronized(simulationLock) {
			engine.createBody(
				JoltBodyDef3D(
					name = "ground",
					shape = JoltShape3D.Plane(ground.nx, ground.ny, ground.nz, ground.constant),
					x = 0.0,
					y = 0.0,
					z = 0.0,
					dynamic = false,
					friction = 0.9,
					restitution = 0.0
				)
			)

			val spawns = generateNonOverlappingCuboidSpawns(
				count = bodyCount,
				random = random,
				planeA = ground,
				planeB = ground
			)
			check(!hasAnySpawnOverlap(spawns)) { "Internal error: overlapping spawn set." }

			spawns.forEachIndexed { index, spawn ->
				engine.createBody(
					JoltBodyDef3D(
						name = "box_${index.toString().padStart(2, '0')}",
						shape = JoltShape3D.Cuboid(spawn.width, spawn.height, spawn.depth),
						x = spawn.x,
						y = spawn.y,
						z = spawn.z,
						friction = random.nextDouble(0.04, 0.10),
						restitution = random.nextDouble(0.45, 0.90),
						linearDamping = random.nextDouble(0.0, 0.02),
						angularDamping = random.nextDouble(0.0, 0.02),
						vx = random.nextDouble(-1.0, 1.0),
						vy = 0.0,
						vz = random.nextDouble(-1.0, 1.0),
						wx = random.nextDouble(-21.7, 21.7),
						wy = random.nextDouble(-0.7, 0.7),
						wz = random.nextDouble(-0.7, 0.7)
					)
				)
			}
		}

		fun closeRuntime() {
			synchronized(simulationLock) {
				if (closed) return
				engine.releaseRigidGrab()
				engine.close()
				closed = true
			}
		}

		val frames = sequence {
			try {
				while (true) {
					val lines = synchronized(simulationLock) {
						if (closed) return@synchronized null
						engine.step(dt)
						engine.debugWireLines3D(axisLength = 0.16f)
					} ?: break
					yield(lines)
				}
			} finally {
				closeRuntime()
			}
		}

		return JoltPickupLoopRuntime(
			frames = frames,
			grabFn = { queryPoint, controllerPosition, controllerRotation, maxOutsideDistance ->
				synchronized(simulationLock) {
					if (closed) {
						return@synchronized null
					}
					engine.pickAndBeginRigidGrab(
						queryPoint = queryPoint,
						controllerPosition = controllerPosition,
						controllerRotation = controllerRotation,
						maxOutsideDistance = maxOutsideDistance
					)
				}
			},
			continueFn = { controllerPosition, controllerRotation ->
				synchronized(simulationLock) {
					if (closed) {
						return@synchronized false
					}
					engine.updateRigidGrabTransform(controllerPosition, controllerRotation)
				}
			},
			releaseFn = {
				synchronized(simulationLock) {
					if (closed) {
						return@synchronized null
					}
					engine.releaseRigidGrab()
				}
			},
			grabbedBodyNameFn = {
				synchronized(simulationLock) {
					if (closed) {
						return@synchronized null
					}
					engine.grabbedBodyName
				}
			},
			closeFn = ::closeRuntime
		)
	}

	private fun runOffCenterCollisionDemo() {
		JoltPhysicsWorld3D().use { engine ->
			engine.createBody(
				JoltBodyDef3D(
					name = "ground",
					shape = JoltShape3D.Plane(ny = 1.0, constant = 0.0),
					x = 0.0,
					y = 0.0,
					z = 0.0,
					dynamic = false,
					friction = 0.05,
					restitution = 0.9
				)
			)

			engine.createBody(
				JoltBodyDef3D(
					name = "targetBox",
					shape = JoltShape3D.Cuboid(1.0, 1.0, 1.0),
					x = 0.0,
					y = 0.55,
					z = 0.0,
					friction = 0.01,
					restitution = 0.5
				)
			)

			engine.createBody(
				JoltBodyDef3D(
					name = "strikerSphere",
					shape = JoltShape3D.Sphere(0.35),
					x = -3.2,
					y = 1.05,
					z = 0.65,
					vx = 12.0,
					vy = 0.0,
					vz = -1.5,
					friction = 0.01,
					restitution = 0.8
				)
			)

			val dt = 1.0 / 60.0
			val steps = 60 * 4
			repeat(steps) { i ->
				val states = engine.step(dt)
				if (i % 6 == 0) {
					val t = i * dt
					println("t=${"%.2f".format(t)}")
					for (state in states.filter { it.name != "ground" }) {
						println(
							"  ${state.name}: " +
								"p=(${state.position.x.format3()}, ${state.position.y.format3()}, ${state.position.z.format3()}) " +
								"v=(${state.linearVelocity.x.format3()}, ${state.linearVelocity.y.format3()}, ${state.linearVelocity.z.format3()}) " +
								"q=(${state.rotation.x.format3()}, ${state.rotation.y.format3()}, ${state.rotation.z.format3()}, ${state.rotation.w.format3()}) " +
								"w=(${state.angularVelocity.x.format3()}, ${state.angularVelocity.y.format3()}, ${state.angularVelocity.z.format3()})"
						)
					}
				}
			}

			val debugLines = engine.debugWireLines3D(axisLength = 0.4f)
			println("debugWireLines3D produced ${debugLines.size} lines")
		}
	}
}

private fun Float.format3(): String = "%.3f".format(this)
private fun Double.format3(): String = "%.3f".format(this)

private data class PlaneDef(val nx: Double, val ny: Double, val nz: Double, val constant: Double)

private data class CuboidSpawn(
	val width: Double,
	val height: Double,
	val depth: Double,
	val x: Double,
	val y: Double,
	val z: Double
)

private fun generateNonOverlappingCuboidSpawns(
	count: Int,
	random: Random,
	planeA: PlaneDef,
	planeB: PlaneDef
): List<CuboidSpawn> {
	val result = ArrayList<CuboidSpawn>(count)
	val spawnPadding = 0.08

	repeat(count) { index ->
		var chosen: CuboidSpawn? = null
		var attempts = 0
		while (chosen == null && attempts < 30_000) {
			attempts += 1

			val width = random.nextDouble(0.35, 0.95)
			val height = random.nextDouble(0.35, 2.60)
			val depth = random.nextDouble(0.35, 0.95)
			val x = random.nextDouble(-4.4, 4.4)
			val z = random.nextDouble(-3.6, 3.6)
			val y = random.nextDouble(4.8, 43.6)

			val candidate = CuboidSpawn(width, height, depth, x, y, z)
			val basePlaneY = maxOf(
				planeHeightAt(planeA, x, z),
				planeHeightAt(planeB, x, z)
			)
			val candidateBottom = candidate.y - candidate.height * 0.5
			if (candidateBottom <= basePlaneY + 0.5) {
				continue
			}
			if (result.any { cuboidsOverlap(candidate, it, spawnPadding) }) {
				continue
			}
			chosen = candidate
		}

		check(chosen != null) {
			"Unable to place cuboid $index without overlap after $attempts attempts."
		}
		result += chosen
	}

	return result
}

private fun hasAnySpawnOverlap(spawns: List<CuboidSpawn>): Boolean {
	for (i in 0 until spawns.size) {
		for (j in i + 1 until spawns.size) {
			if (cuboidsOverlap(spawns[i], spawns[j], margin = 0.0)) {
				return true
			}
		}
	}
	return false
}

private fun cuboidsOverlap(a: CuboidSpawn, b: CuboidSpawn, margin: Double): Boolean {
	val overlapX = abs(a.x - b.x) < (a.width + b.width) * 0.5 + margin
	val overlapY = abs(a.y - b.y) < (a.height + b.height) * 0.5 + margin
	val overlapZ = abs(a.z - b.z) < (a.depth + b.depth) * 0.5 + margin
	return overlapX && overlapY && overlapZ
}

private fun planeHeightAt(plane: PlaneDef, x: Double, z: Double): Double {
	val denom = plane.ny
	check(abs(denom) > 1e-6) { "Plane ny must be non-zero, got ${plane.ny}" }
	return -(plane.nx * x + plane.nz * z + plane.constant) / denom
}

