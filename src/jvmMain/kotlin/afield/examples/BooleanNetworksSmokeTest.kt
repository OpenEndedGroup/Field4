package afield.examples

import afield.stdlib.findAttractors
import afield.stdlib.kauffmanNetwork
import afield.stdlib.notateAttractorsHtml
import afield.stdlib.notateHtml
import afield.stdlib.segmentPolarityNetwork
import afield.stdlib.threeNodeNetwork
import afield.stdlib.toggleNetwork
import afield.stdlib.drawAttractorBasins
import afield.stdlib.drawNetworkGraph
import afield.stdlib.drawStateTransitionGraph
import afield.stdlib.drawUnrolled
import afield.stdlib.drawRhythmGrid
import afield.stdlib.trajectory
import afield.stdlib.trajectoryUntilCycle
import afield.stdlib.repeatedCycle

/*
FIELD_TEST
id: stdlib.boolean-networks.smoke
runner: jvm-main
entrypoint: afield.examples.BooleanNetworksSmokeTest
tags: [smoke, examples, stdlib, boolean-network, graph]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "Boolean network smoke tests passed"
*/
object BooleanNetworksSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Boolean network smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            println("Running Boolean network smoke tests...")
            testToggleNetwork()
            testThreeNodeNetwork()
            testSegmentPolarityNetwork()
            testKauffmanNetwork()
            testTrajectory()
            testTrajectoryUntilCycle()
            testAttractors()
            testRepeatedCycle()
            testDrawUnrolled()
            testDrawNetworkGraph()
            testDrawStateTransitionGraph()
            testDrawAttractorBasins()
            testDrawRhythmGrid()
            testNotation()
            println("Boolean network smoke tests passed")
            true
        } catch (e: Exception) {
            System.err.println("FAILED: ${e.message}")
            e.printStackTrace(System.err)
            false
        }
    }

    private fun testToggleNetwork() {
        val net = toggleNetwork()
        require(net.size == 2)
        val s0 = booleanArrayOf(true, false)
        val s1 = net.step(s0)
        require(s1[0] && s1[1]) { "Step 1: expected [true,true], got ${net.stateString(s1)}" }
        val s2 = net.step(s1)
        require(!s2[0] && s2[1]) { "Step 2: expected [false,true], got ${net.stateString(s2)}" }
        val attractors = net.findAttractors()
        require(attractors.size == 1) { "Toggle network should have 1 attractor" }
        require(attractors[0].period == 4) { "Toggle attractor period should be 4, got ${attractors[0].period}" }
        println("  ✓ toggleNetwork: period-4 cycle, basin=${attractors[0].basin.size}")
    }

    private fun testThreeNodeNetwork() {
        val net = threeNodeNetwork()
        require(net.size == 3)
        val s = net.step(booleanArrayOf(false, false, false))
        require(!s[0] && !s[1] && s[2]) {
            "threeNodeNetwork step from [0,0,0]: expected [0,0,1], got ${net.stateString(s)}"
        }
        println("  ✓ threeNodeNetwork step")
    }

    private fun testSegmentPolarityNetwork() {
        val net = segmentPolarityNetwork()
        require(net.size == 4)
        val attrs = net.findAttractors()
        require(attrs.isNotEmpty()) { "segmentPolarityNetwork should have attractors" }
        require(attrs.sumOf { it.basin.size } == 1 shl net.size) {
            "Basin sizes should cover all ${1 shl net.size} states"
        }
        println("  ✓ segmentPolarityNetwork: ${attrs.size} attractor(s)")
    }

    private fun testKauffmanNetwork() {
        val net = kauffmanNetwork(n = 5, k = 2, seed = 42L)
        require(net.size == 5)
        val attrs = net.findAttractors()
        require(attrs.isNotEmpty())
        require(attrs.sumOf { it.basin.size } == 1 shl net.size)
        println("  ✓ kauffmanNetwork(5,2,42): ${attrs.size} attractor(s), basin total ${attrs.sumOf { it.basin.size }}")
    }

    private fun testTrajectory() {
        val net = toggleNetwork()
        val traj = net.trajectory(booleanArrayOf(true, false), steps = 6)
        require(traj.size == 7) { "Expected 7 states, got ${traj.size}" }
        require(traj[0][0] && !traj[0][1]) { "traj[0] should be [T,F]" }
        require(traj[1][0] && traj[1][1]) { "traj[1] should be [T,T]" }
        require(!traj[2][0] && traj[2][1]) { "traj[2] should be [F,T]" }
        require(traj[4][0] && !traj[4][1]) { "traj[4] should equal traj[0]=[T,F]" }
        println("  ✓ trajectory: length ${traj.size}, period-4 cycling confirmed")
    }

    private fun testTrajectoryUntilCycle() {
        val net = toggleNetwork()
        val traj = net.trajectoryUntilCycle(booleanArrayOf(true, false))
        require(traj.size >= 5) { "Expected at least 5 states for the period-4 cycle, got ${traj.size}" }
        val last = traj.last()
        val repeated = traj.dropLast(1).any { st -> st.contentEquals(last) }
        require(repeated) { "Last state should repeat an earlier state" }
        println("  ✓ trajectoryUntilCycle detects the period-4 cycle (${traj.size} states)")
    }

    private fun testAttractors() {
        val net = toggleNetwork()
        val attrs = net.findAttractors()
        require(attrs.size == 1) { "Toggle network should have exactly 1 attractor, got ${attrs.size}" }
        val totalBasin = attrs.sumOf { it.basin.size }
        require(totalBasin == 1 shl net.size) {
            "Sum of basin sizes ($totalBasin) should equal ${1 shl net.size}"
        }
        println("  ✓ findAttractors: ${attrs.size} attractor(s), total basin $totalBasin")

        val state = booleanArrayOf(true, false, true)
        val net3 = threeNodeNetwork()
        val i = net3.stateToInt(state)
        val back = net3.intToState(i)
        require(state.contentEquals(back)) { "stateToInt/intToState round-trip failed" }
        println("  ✓ stateToInt/intToState round-trip")
    }

    private fun testRepeatedCycle() {
        val attr = toggleNetwork().findAttractors().single()
        val repeated = attr.repeatedCycle(10)
        require(repeated.size == 10) { "repeatedCycle should return 10 states, got ${repeated.size}" }
        require(repeated[0].contentEquals(repeated[4])) { "period-4 cycle should wrap at step 4" }
        println("  ✓ repeatedCycle repeats attractor phases")
    }

    private fun testDrawUnrolled() {
        val net = threeNodeNetwork()
        val traj = net.trajectory(booleanArrayOf(true, false, true), steps = 8)
        val flines = net.drawUnrolled(traj)
        require(flines.isNotEmpty())
        require(flines.all { it.nodes.isNotEmpty() }) { "All FLines should be non-empty" }
        println("  ✓ drawUnrolled: ${flines.size} FLine(s) for ${traj.size}×${net.size} grid")
    }

    private fun testDrawNetworkGraph() {
        val net = threeNodeNetwork()
        val flines = net.drawNetworkGraph()
        require(flines.isNotEmpty())
        println("  ✓ drawNetworkGraph: ${flines.size} FLine(s)")
    }

    private fun testDrawStateTransitionGraph() {
        val net = toggleNetwork()
        val flines = net.drawStateTransitionGraph()
        require(flines.isNotEmpty())
        val bigNet = kauffmanNetwork(n = 8)
        require(bigNet.drawStateTransitionGraph().isEmpty()) { "size>6 should return empty list" }
        println("  ✓ drawStateTransitionGraph: ${flines.size} FLine(s)")
    }

    private fun testDrawAttractorBasins() {
        val net = threeNodeNetwork()
        val attrs = net.findAttractors()
        val flines = net.drawAttractorBasins(attrs, samplesPerAttractor = 4)
        require(flines.isNotEmpty())
        println("  ✓ drawAttractorBasins: ${flines.size} FLine(s)")
    }

    private fun testDrawRhythmGrid() {
        val attr = toggleNetwork().findAttractors().single()
        val flines = drawRhythmGrid(attr.repeatedCycle(12), accentEvery = 4)
        require(flines.isNotEmpty())
        require(flines.all { it.nodes.isNotEmpty() }) { "drawRhythmGrid should only return non-empty FLines" }
        println("  ✓ drawRhythmGrid: ${flines.size} FLine(s)")
    }

    private fun testNotation() {
        val net = threeNodeNetwork()
        val tableHtml = net.notateHtml()
        require("<table" in tableHtml) { "notateHtml should produce a <table>" }
        require("A" in tableHtml && "B" in tableHtml && "C" in tableHtml)

        val attrs = net.findAttractors()
        val attrHtml = net.notateAttractorsHtml(attrs)
        require("Attractor" in attrHtml)
        require("basin" in attrHtml)
        println("  ✓ notateHtml (${tableHtml.length} chars)  notateAttractorsHtml (${attrHtml.length} chars)")

        val table = net.stateTable(booleanArrayOf(true, false, true))
        require("Node" in table && "Rule" in table)
        println("  ✓ stateTable plain-text output")
    }
}
