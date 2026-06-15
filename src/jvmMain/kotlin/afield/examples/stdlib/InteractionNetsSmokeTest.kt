package afield.examples.stdlib

import afield.Box
import afield.Rect
import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.abs

/*
FIELD_TEST
id: stdlib.interaction-nets.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.InteractionNetsSmokeTest
tags: [smoke, stdlib, interaction-nets]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "Interaction nets smoke tests passed"
*/
object InteractionNetsSmokeTest {

    fun runTests(): Boolean {
        return try {
            println("Running interaction nets smoke tests...")
            testBetaRuleHandlesSwappedPairOrientation()
            testEnergyBiasedSchedulerPrefersHotterPair()
            testDuplicationSplitsStateAndReportsEvent()
            testEraseRuleCreatesDecayAgents()
            testDrawingHelpersProvideReusableFLinePrimitives()
            testFLineRendererFeedsOutputAreas()
            println("Interaction nets smoke tests passed")
            true
        } catch (t: Throwable) {
            println("Interaction nets smoke tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Interaction nets smoke tests failed" }
    }

    private fun testBetaRuleHandlesSwappedPairOrientation() {
        val rules = Lambda.rules()
        val reducer = Reducer(rules)
        val net = net {
            val root = node(Lambda.ROOT, id = "root", at = vec(-180.0, -80.0), state = NodeState(energy = 0.7))
            val app = node(Lambda.APP, id = "app", at = vec(-40.0, 0.0), state = NodeState(energy = 0.8))
            val lam = node(Lambda.LAM, id = "lam", at = vec(40.0, 0.0), state = NodeState(energy = 1.4))
            val arg = node(Lambda.CONST, id = "arg", at = vec(170.0, 80.0), state = NodeState(energy = 1.1))
            val binder = node(Lambda.VAR, id = "binder", at = vec(180.0, -70.0), state = NodeState(energy = 0.9))
            val body = node(Lambda.VAR, id = "body", at = vec(170.0, 10.0), state = NodeState(energy = 1.0))

            // Intentionally connect in LAM -> APP order to prove signature normalization.
            lam.principal to app.principal
            app.aux(0) to root.principal
            app.aux(1) to arg.principal
            lam.aux(0) to binder.principal
            lam.aux(1) to body.principal
        }

        val detail = reducer.stepDetailed(net) ?: error("Expected beta step")
        require(detail.ruleName == "beta-linear") { "Expected beta-linear, got ${detail.ruleName}" }
        require(detail.swapped) { "Expected swapped=true when the pair is connected as LAM -> APP" }
        require(detail.createdNodeIds.isEmpty()) { "beta-linear should not create nodes" }
        require(detail.events.singleOrNull()?.kind == "beta") { "beta-linear should emit a beta event" }
        requireConnected(net, net.node("root"), net.node("body"))
        requireConnected(net, net.node("binder"), net.node("arg"))
        require(net.nodes.none { it.id == "app" || it.id == "lam" }) { "APP/LAM nodes should be removed" }
    }

    private fun testEnergyBiasedSchedulerPrefersHotterPair() {
        val rules = Lambda.rules()
        val reducer = Reducer(rules, order = ActivationOrder.HIGHEST_PAIR_ENERGY)
        val net = net {
            val lowL = node(Lambda.ROOT, id = "lowL", at = vec(-320.0, -40.0), state = NodeState(energy = 0.2))
            val lowR = node(Lambda.ROOT, id = "lowR", at = vec(-320.0, 50.0), state = NodeState(energy = 0.2))
            val dupLow = node(Lambda.DUP, id = "dupLow", at = vec(-160.0, 0.0), state = NodeState(energy = 0.6))
            val constLow = node(Lambda.CONST, id = "constLow", at = vec(-40.0, 0.0), state = NodeState(energy = 0.5))

            val highL = node(Lambda.ROOT, id = "highL", at = vec(80.0, -40.0), state = NodeState(energy = 0.2))
            val highR = node(Lambda.ROOT, id = "highR", at = vec(80.0, 50.0), state = NodeState(energy = 0.2))
            val dupHigh = node(Lambda.DUP, id = "dupHigh", at = vec(220.0, 0.0), state = NodeState(energy = 1.2))
            val constHigh = node(Lambda.CONST, id = "constHigh", at = vec(340.0, 0.0), state = NodeState(energy = 1.8, hue = 0.14))

            dupLow.principal to constLow.principal
            dupLow.aux(0) to lowL.principal
            dupLow.aux(1) to lowR.principal

            dupHigh.principal to constHigh.principal
            dupHigh.aux(0) to highL.principal
            dupHigh.aux(1) to highR.principal
        }

        val detail = reducer.stepDetailed(net) ?: error("Expected a DUP/CONST rewrite")
        require(detail.ruleName == "dup-const") { "Expected dup-const, got ${detail.ruleName}" }
        require(detail.removedNodeIds.toSet() == setOf("dupHigh", "constHigh")) {
            "Expected the hotter pair to fire first, got ${detail.removedNodeIds}"
        }
        require(net.nodes.any { it.id == "dupLow" }) { "The colder pair should still remain after one step" }
    }

    private fun testDuplicationSplitsStateAndReportsEvent() {
        val rules = Lambda.rules()
        val reducer = Reducer(rules, order = ActivationOrder.HIGHEST_PAIR_ENERGY)
        val net = Lambda.duplicatedConstantDemo()

        val detail = reducer.stepDetailed(net) ?: error("Expected dup-const step")
        require(detail.ruleName == "dup-const") { "Expected dup-const, got ${detail.ruleName}" }
        require(detail.events.singleOrNull()?.kind == "duplicate") { "dup-const should emit duplicate" }

        val created = detail.createdNodeIds.map(net::node)
        require(created.size == 2) { "dup-const should create two copies, got ${created.size}" }
        require(created.all { it.type == Lambda.CONST }) { "dup-const should materialize two CONST nodes" }
        created.forEach {
            requireClose(it.state.energy, 0.81, message = "Duplicated constant should split energy with attenuation")
        }
        require(created[0].state.hue != created[1].state.hue) { "Duplicated branches should diverge in hue" }
    }

    private fun testEraseRuleCreatesDecayAgents() {
        val rules = Lambda.rules()
        val reducer = Reducer(rules)
        val net = Lambda.eraseApplicationDemo()

        val detail = reducer.stepDetailed(net) ?: error("Expected erase-app step")
        require(detail.ruleName == "erase-app") { "Expected erase-app, got ${detail.ruleName}" }
        require(detail.events.singleOrNull()?.kind == "prune") { "erase-app should emit prune" }

        val created = detail.createdNodeIds.map(net::node)
        require(created.size == 2) { "erase-app should spawn two ERASE agents" }
        require(created.all { it.type == Lambda.ERASE }) { "erase-app should only create ERASE agents" }
        created.forEach {
            requireClose(it.state.energy, 0.39375, message = "Decay agents should attenuate inherited energy")
        }
    }

    private fun testDrawingHelpersProvideReusableFLinePrimitives() {
        val panel = Rect(10.0, 20.0, 30.0, 40.0).toFLine(
            color = vec(0.2, 0.3, 0.4, 0.5),
            filled = true
        )
        require(panel.nodes.size == 5) { "Rect.toFLine should create a closed rectangular path" }
        require(panel.nodes.last() is FLine.Close) { "Rect.toFLine should close the contour" }
        require(panel.filled && panel.stroked) { "Rect.toFLine should keep sensible stroke/fill defaults" }

        val fitted = listOf(panel).fitInto(Rect(0.0, 0.0, 100.0, 100.0), padding = 10.0)
        val fittedBounds = fitted.single().fast2DBounds()
        require(fittedBounds.left >= 10.0 - 1e-6 && fittedBounds.top >= 10.0 - 1e-6) {
            "fitInto should keep geometry inside the padded frame"
        }
        require(fittedBounds.right <= 90.0 + 1e-6 && fittedBounds.bottom <= 90.0 + 1e-6) {
            "fitInto should keep geometry inside the padded frame"
        }

        val arrowLine = arrow(0.0, 0.0, 120.0, 0.0)
        require(arrowLine.nodes.any { it is FLine.Close }) { "arrow should emit a real closed arrowhead" }
        val arrowBounds = arrowLine.fast2DBounds()
        require(arrowBounds.left >= -1e-6 && arrowBounds.right <= 120.0 + 1e-6) {
            "arrow bounds should stay anchored to the requested segment"
        }
    }

    private fun testFLineRendererFeedsOutputAreas() {
        val outputs = linkedMapOf<String, Any?>()
        val box = Box("interaction-net-render-test").also { testBox ->
            testBox.output.displayProxy = { channel, message -> outputs[channel] = message }
        }

        val net = Lambda.duplicatedConstantDemo()
        val rendered = box.showInteractionNet(
            net,
            channel = "net",
            summaryChannel = "summary",
            title = "Duplicated constant",
            w = 360.0,
            h = 220.0
        )
        box.showInteractionSummary(
            channel = "cards",
            net.summaryHtml("dup"),
            ReductionStep(
                ruleName = "preview",
                context = PairContext(
                    first = net.node("dup"),
                    second = net.node("k"),
                    firstAuxPeers = listOf(net.connected(net.node("dup").aux(0)), net.connected(net.node("dup").aux(1))),
                    secondAuxPeers = emptyList(),
                    center = vec(0.0, 0.0),
                    radius = 1.0,
                    firstVelocity = vec(0.0, 0.0),
                    secondVelocity = vec(0.0, 0.0),
                    axis = vec(1.0, 0.0),
                    normal = vec(0.0, 1.0)
                ),
                swapped = false,
                removedNodeIds = listOf("dup", "k"),
                createdNodeIds = listOf("k_0", "k_1"),
                events = listOf(RewriteEvent("duplicate", vec(0.0, 0.0), 1.0))
            ).summaryHtml("step")
        )
        box.showInteractionOverview(
            graphicLines = rendered,
            cards = listOf(
                net.summaryHtml("dup"),
                ReductionStep(
                    ruleName = "preview",
                    context = PairContext(
                        first = net.node("dup"),
                        second = net.node("k"),
                        firstAuxPeers = listOf(net.connected(net.node("dup").aux(0)), net.connected(net.node("dup").aux(1))),
                        secondAuxPeers = emptyList(),
                        center = vec(0.0, 0.0),
                        radius = 1.0,
                        firstVelocity = vec(0.0, 0.0),
                        secondVelocity = vec(0.0, 0.0),
                        axis = vec(1.0, 0.0),
                        normal = vec(0.0, 1.0)
                    ),
                    swapped = false,
                    removedNodeIds = listOf("dup", "k"),
                    createdNodeIds = listOf("k_0", "k_1"),
                    events = listOf(RewriteEvent("duplicate", vec(0.0, 0.0), 1.0))
                ).summaryHtml("step")
            ),
            channel = "overview",
            graphicTitle = "Preview",
            graphicWidth = 360.0,
            graphicHeight = 220.0
        )

        require(rendered.isNotEmpty()) { "toFLines should produce geometry" }
        require(outputs["net"] is String && (outputs["net"] as String).contains("<svg")) {
            "showInteractionNet should render SVG into the output area"
        }
        require(outputs["summary"] is String && (outputs["summary"] as String).contains("Duplicated constant")) {
            "showInteractionNet should emit summary HTML"
        }
        require(outputs["cards"] is String && (outputs["cards"] as String).contains("display:grid")) {
            "showInteractionSummary should arrange cards in an HTML grid"
        }
        require(outputs["overview"] is String && (outputs["overview"] as String).contains("Preview")) {
            "showInteractionOverview should combine cards and geometry in one output disclosure"
        }
        val exported = net.toSvg(width = 360, height = 220)
        require(exported.contains("<svg")) { "SVG export should still be available via the FLine renderer" }
    }

    private fun requireConnected(net: Net, a: Node, b: Node) {
        require(net.connected(a.principal) == b.principal) { "Expected ${a.id} to connect to ${b.id}" }
        require(net.connected(b.principal) == a.principal) { "Expected ${b.id} to connect to ${a.id}" }
    }

    private fun requireClose(actual: Double, expected: Double, epsilon: Double = 1e-6, message: String) {
        require(abs(actual - expected) <= epsilon) { "$message: expected $expected, got $actual" }
    }
}
