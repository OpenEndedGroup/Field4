package afield.kratos

import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Reusable cuboid assembly: resize `armEnd` without changing authored identity. */
object KratosDemo {
    fun body(armEnd: Double = .71): SimulationCSG = SimulationCSG("body",
        SimulationBox("base",SimulationBounds(-.73,-.63,-.52,.23,.53,.62)),
        SimulationBox("arm",SimulationBounds(-.13,-.32,-.42,armEnd,.33,.42)))
}

/*
FIELD_TEST
id: kratos.embedded.thermal
runner: jvm-main
entrypoint: afield.kratos.KratosSmokeTest
tags: [smoke, kratos, optional, simulation]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "Kratos smoke passed"
*/
object KratosSmokeTest {
    @JvmStatic fun main(args: Array<String>) {
        val capability = Kratos.probe().get(20,TimeUnit.SECONDS)
        println(capability)
        if (!capability.available) {
            println("Kratos smoke passed (SKIPPED: optional environment absent)")
            return
        }
        KratosSession.openAsync(SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0)).get(20,TimeUnit.SECONDS).use { session ->
            val a = session.solveAsync(KratosDemo.body()).get(20,TimeUnit.SECONDS)!!
            val stale = session.solveAsync(KratosDemo.body(.63),preview=true)
            val newest = session.solveAsync(KratosDemo.body(.43),preview=true)
            check(stale.get(20,TimeUnit.SECONDS) == null)
            val b = newest.get(20,TimeUnit.SECONDS)!!
            check(a.domain === b.domain && b.domain.buildCount == 1)
            val changed = a.distance.indices.count { (a.distance[it]>0) != (b.distance[it]>0) }
            val delta = a.temperature.indices.maxOf { abs(a.temperature[it]-b.temperature[it]) }
            check(changed > 0 && delta > .01 && session.latest === b)
            println("NO_REMESH background=${b.domain.id} buildCount=1 changedNodes=$changed maxTemperatureDelta=$delta staleRejected=true")
            println("FIRST ${a.diagnostics}")
            println("SECOND ${b.diagnostics}")
            check(!b.sliceMesh().isEmpty)
            println("Kratos smoke passed")
        }
    }
}
