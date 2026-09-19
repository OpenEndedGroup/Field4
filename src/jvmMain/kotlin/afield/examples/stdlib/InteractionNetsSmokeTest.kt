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
            testEraseCascadeRunsToAFixedPoint()
            testDifferentlyLabelledFansCommuteAndThenKeepReducing()
            testErasingTheIdentityReachesTheEraseEraseRule()
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

    /**
     * The erase cascade, **all the way down** rather than one step.
     *
     * Every other test in this file — and all four `docs.interaction-nets.*` boxes on
     * `public/docs/interaction_nets_examples.md` — calls `stepDetailed` exactly once, which is why they passed
     * unchanged on 2026-09-18 when `erase-erase` and `fan-fan` were added and `(ERASE, ERASE)` stopped being a
     * normal form. A rule book gains a rule at the *end* of a reduction, so a single-step test cannot see it.
     *
     * This drives `eraseApplicationDemo` to a fixed point and pins the whole sequence.
     */
    private fun testEraseCascadeRunsToAFixedPoint() {
        val reducer = Reducer(Lambda.rules(), relaxIterations = 0)
        val net = Lambda.eraseApplicationDemo()
        val before = net.nodes.size
        val fired = ArrayList<String>()
        reducer.reduceDetailed(net, maxSteps = 32) { _, _, detail -> fired += detail.ruleName }

        require(fired == listOf("erase-app", "erase-const", "erase-var")) {
            "Expected the cascade erase-app, erase-const, erase-var; got $fired"
        }
        require(before == 4) { "eraseApplicationDemo should start with 4 agents, found $before" }
        require(net.nodes.isEmpty()) {
            "The cascade should consume the whole net; ${net.nodes.size} left: ${net.nodes.map { it.id }}"
        }
        // The fixed point is a fixed point: asking again does nothing at all.
        require(reducer.stepDetailed(net) == null) { "An empty net should offer no further rewrite" }
        println("  erase cascade: 4 agents -> 0 in ${fired.size} steps (${fired.joinToString(", ")})")
    }

    /**
     * Two fans with **different** labels, driven to a fixed point.
     *
     * `fan-fan`'s commuting branch is the half of the rule that no hand-wired net in this tree exercised: the
     * demos all carry the default label 0, so they annihilate. Here the labels differ, so one `fan-fan` becomes
     * four fans, and those four then have to go somewhere — which is the multi-step part, and the part a single
     * `stepDetailed` cannot reach.
     *
     * Four constants in, four copies out, and not one fan standing.
     */
    private fun testDifferentlyLabelledFansCommuteAndThenKeepReducing() {
        val reducer = Reducer(Lambda.rules(), relaxIterations = 0)
        val subject = net {
            val fanA = node(Lambda.DUP, id = "fanA", at = vec(-80.0, 0.0), state = Lambda.fan(1))
            val fanB = node(Lambda.DUP, id = "fanB", at = vec(80.0, 0.0), state = Lambda.fan(2))
            val c1 = node(Lambda.CONST, id = "c1", at = vec(-180.0, -70.0), state = NodeState(energy = 1.0))
            val c2 = node(Lambda.CONST, id = "c2", at = vec(-180.0, 70.0), state = NodeState(energy = 1.0))
            val c3 = node(Lambda.CONST, id = "c3", at = vec(180.0, -70.0), state = NodeState(energy = 1.0))
            val c4 = node(Lambda.CONST, id = "c4", at = vec(180.0, 70.0), state = NodeState(energy = 1.0))

            fanA.principal to fanB.principal
            fanA.aux(0) to c1.principal
            fanA.aux(1) to c2.principal
            fanB.aux(0) to c3.principal
            fanB.aux(1) to c4.principal
        }

        val fired = ArrayList<String>()
        val events = ArrayList<String>()
        reducer.reduceDetailed(subject, maxSteps = 64) { _, _, detail ->
            fired += detail.ruleName
            events += detail.events.map { it.kind }
        }

        require(fired.first() == "fan-fan") { "Expected fan-fan first, got ${fired.first()}" }
        require(events.first() == "branch") {
            "Different labels must commute, not annihilate — the first event was ${events.first()}"
        }
        require(fired == listOf("fan-fan", "dup-const", "dup-const", "dup-const", "dup-const")) {
            "A commuting fan-fan leaves four fans facing four constants, so the sequence is one branch and four " +
                "duplications; got $fired"
        }
        require(subject.nodes.size == 8) {
            "Four constants through a commuting pair of fans is eight copies, found ${subject.nodes.size}"
        }
        require(subject.nodes.none { it.type == Lambda.DUP }) {
            "Fans survived to the fixed point: ${subject.nodes.filter { it.type == Lambda.DUP }.map { it.id }}"
        }
        require(subject.nodes.all { it.type == Lambda.CONST }) {
            "Only constants should be left, found ${subject.nodes.map { it.type.name }.distinct()}"
        }
        println(
            "  labelled fan-fan commuting: ${fired.size} steps (${fired.joinToString(", ")}) " +
                "-> ${subject.nodes.size} constants"
        )
    }

    /**
     * `erase-erase`, reached the only way it can be reached: several steps in.
     *
     * Erasing `λx. x` puts an `ERASE` on the binder and an `ERASE` on the body — and in the identity the binder
     * *is* the body, so the two face each other down the wire that the vanishing `LAM` used to hold. Before
     * 2026-09-18 that pair was a normal form and this net stopped with two agents standing that nothing could
     * consume, which looks exactly like a finished reduction until you count the nodes.
     *
     * Two things had to land for this to work and both are multi-step-only: the rule itself, and the wire chasing
     * in `Reducer.apply` that lets `erase-lam` reattach through the pair it is deleting.
     */
    private fun testErasingTheIdentityReachesTheEraseEraseRule() {
        val reducer = Reducer(Lambda.rules(), relaxIterations = 0)
        val subject = net {
            val er = node(Lambda.ERASE, id = "er", at = vec(-90.0, 0.0), state = NodeState(energy = 0.9))
            val lam = node(Lambda.LAM, id = "lam", at = vec(90.0, 0.0), state = NodeState(energy = 1.1))
            er.principal to lam.principal
            lam.aux(0) to lam.aux(1) // λx. x — the binder is the body
        }

        val fired = ArrayList<String>()
        reducer.reduceDetailed(subject, maxSteps = 32) { _, _, detail -> fired += detail.ruleName }

        require(fired == listOf("erase-lam", "erase-erase")) {
            "Erasing the identity should be erase-lam then erase-erase; got $fired"
        }
        require(subject.nodes.isEmpty()) {
            "Nothing should survive erasing the identity; ${subject.nodes.size} left: " +
                subject.nodes.map { "${it.id}:${it.type.name}" }
        }
        println("  erasing the identity: ${fired.joinToString(" then ")} -> empty net")
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
