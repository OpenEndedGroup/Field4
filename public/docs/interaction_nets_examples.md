# Interaction Nets examples

*Every public declaration used below is listed, with its KDoc, in the codebase reference: [`interactionnets.kt`](reference/visualization.md#interactionnets-kt). Start from [what's already in the box](reference/index.md).*

These four are the reduction rules `InteractionNetsSmokeTest` checks; its other two checks cover the drawing helpers rather than the reducer. Each example runs a single `stepDetailed` and shows the result as ordinary Field output: three summary cards across the top, and below them the net before the step and the net after it, both relaxed.

The energies are art direction rather than semantics, which is how `Lambda.rules()` describes them in its own KDoc: every rule splits or attenuates node energy so that a reduction visibly decays instead of merely rewiring. The same KDoc states the scope. There is no fan/fan commuting rule and no lambda frontend, so this is enough to watch beta reduction happen and to build your own vocabulary beside, and not a complete optimal reducer.

## Beta bridge with swapped pair orientation

`APP ⟂ LAM` has to reduce the same way whichever order the active pair was connected in, so this net wires it backwards, `LAM -> APP`, and the rewrite card reports `swapped: yes`. What `beta-linear` then does is pure rewiring: the lambda's body is connected to the application's context and its binder to the application's argument, `created` is `none`, and APP and LAM are dropped. It is the one rule of the four that attenuates nothing: total energy falls from 5.90 to 3.70, which is exactly the 2.20 the two removed nodes were carrying.

<!--
FIELD_TEST
id: docs.interaction-nets.beta-bridge
runner: backdoor-box
tags: [docs, interaction-nets, beta, reducer]
timeoutSec: 45
withPlaywright: true
waitSec: 2.0
expect:
  outputContains:
    - "interaction beta bridge ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 12
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/interaction_nets_examples/beta_bridge.png" wait="2.0" capture="box" open-outputs="true" embed=true name="interaction-beta-bridge" w="1240" padding="12"}
:::static
import afield.Rect

val reducer = Reducer(Lambda.rules())
val net = net {
    val root = node(Lambda.ROOT, id = "root", at = vec(-180.0, -80.0), state = NodeState(energy = 0.7))
    val app = node(Lambda.APP, id = "app", at = vec(-40.0, 0.0), state = NodeState(energy = 0.8))
    val lam = node(Lambda.LAM, id = "lam", at = vec(40.0, 0.0), state = NodeState(energy = 1.4))
    val arg = node(Lambda.CONST, id = "arg", at = vec(170.0, 80.0), state = NodeState(energy = 1.1))
    val binder = node(Lambda.VAR, id = "binder", at = vec(180.0, -70.0), state = NodeState(energy = 0.9))
    val body = node(Lambda.VAR, id = "body", at = vec(170.0, 10.0), state = NodeState(energy = 1.0))

    lam.principal to app.principal
    app.aux(0) to root.principal
    app.aux(1) to arg.principal
    lam.aux(0) to binder.principal
    lam.aux(1) to body.principal
}

net.relax(20)
val beforeLines = net.toFLines()
val beforeSummary = net.summaryHtml("Before beta", maxNodes = 5)
val detail = reducer.stepDetailed(net)!!
net.relax(28)
val afterLines = net.toFLines()
val beforeFrame = Rect(52.0, 54.0, 404.0, 232.0)
val afterFrame = Rect(584.0, 54.0, 404.0, 232.0)
val comparisonLines = listOf(
    beforeFrame.toFLine(filled = true, color = vec(0.08, 0.13, 0.18, 0.56)),
    afterFrame.toFLine(filled = true, color = vec(0.18, 0.11, 0.08, 0.54)),
    arrow(
        beforeFrame.right + 34.0, 170.0,
        afterFrame.x - 34.0, 170.0,
        color = vec(0.92, 0.90, 0.84, 0.95),
        thickness = 2.0
    ),
) +
    beforeLines.fitInto(beforeFrame, padding = 26.0) +
    afterLines.fitInto(afterFrame, padding = 26.0)

showInteractionOverview(
    graphicLines = comparisonLines,
    cards = listOf(
        beforeSummary,
        detail.summaryHtml("Rewrite"),
        net.summaryHtml("After beta", maxNodes = 5)
    ),
    channel = "overview",
    graphicTitle = "Before / rewrite / after geometry",
    graphicWidth = 1040.0,
    graphicHeight = 340.0
)

println("interaction beta bridge ready")
```

## Energy-biased scheduling

This net offers the reducer two structurally identical `DUP ⟂ CONST` pairs, one at energies 0.6 and 0.5 and one at 1.2 and 1.8, and asks for a single step. Under `ActivationOrder.HIGHEST_PAIR_ENERGY` the hotter pair fires and the colder one is left standing, so which rewrite happens next is a property of the state the nodes are carrying rather than of the order they were built in.

<!--
FIELD_TEST
id: docs.interaction-nets.energy-scheduler
runner: backdoor-box
tags: [docs, interaction-nets, scheduler, energy]
timeoutSec: 45
withPlaywright: true
waitSec: 2.0
expect:
  outputContains:
    - "interaction scheduler ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 12
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/interaction_nets_examples/energy_scheduler.png" wait="2.0" capture="box" open-outputs="true" embed=true name="interaction-energy-scheduler" w="1240" padding="12"}
:::static
import afield.Rect

val reducer = Reducer(Lambda.rules(), order = ActivationOrder.HIGHEST_PAIR_ENERGY)
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

net.relax(20)
val beforeLines = net.toFLines()
val beforeSummary = net.summaryHtml("Before scheduler step", maxNodes = 5)
val detail = reducer.stepDetailed(net)!!
net.relax(28)
val afterLines = net.toFLines()
val beforeFrame = Rect(52.0, 54.0, 404.0, 232.0)
val afterFrame = Rect(584.0, 54.0, 404.0, 232.0)
val comparisonLines = listOf(
    beforeFrame.toFLine(filled = true, color = vec(0.08, 0.13, 0.18, 0.56)),
    afterFrame.toFLine(filled = true, color = vec(0.18, 0.11, 0.08, 0.54)),
    arrow(
        beforeFrame.right + 34.0, 170.0,
        afterFrame.x - 34.0, 170.0,
        color = vec(0.92, 0.90, 0.84, 0.95),
        thickness = 2.0
    ),
) +
    beforeLines.fitInto(beforeFrame, padding = 26.0) +
    afterLines.fitInto(afterFrame, padding = 26.0)

showInteractionOverview(
    graphicLines = comparisonLines,
    cards = listOf(
        beforeSummary,
        detail.summaryHtml("Selected pair"),
        net.summaryHtml("After one step", maxNodes = 5)
    ),
    channel = "overview",
    graphicTitle = "Before / rewrite / after geometry",
    graphicWidth = 1040.0,
    graphicHeight = 340.0
)

println("interaction scheduler ready")
```

## DUP splits state into two descendants

`dup-const` replaces the constant with one copy per `DUP` auxiliary, and the copies are not identical to it or to each other. Each inherits half the original's energy attenuated by 0.9, which is 0.81 from the constant's 1.8, and the two are pushed 0.05 apart in hue, so the branches are the same shape in different colours, which is what makes a second duplication further down visible as a gradient rather than as a repeat.

<!--
FIELD_TEST
id: docs.interaction-nets.duplication
runner: backdoor-box
tags: [docs, interaction-nets, duplication, state]
timeoutSec: 45
withPlaywright: true
waitSec: 2.0
expect:
  outputContains:
    - "interaction duplication ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 12
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/interaction_nets_examples/duplication.png" wait="2.0" capture="box" open-outputs="true" embed=true name="interaction-duplication" w="1240" padding="12"}
:::static
import afield.Rect

val reducer = Reducer(Lambda.rules(), order = ActivationOrder.HIGHEST_PAIR_ENERGY)
val net = Lambda.duplicatedConstantDemo()

net.relax(20)
val beforeLines = net.toFLines()
val beforeSummary = net.summaryHtml("Before duplication", maxNodes = 5)
val detail = reducer.stepDetailed(net)!!
net.relax(28)
val afterLines = net.toFLines()
val beforeFrame = Rect(52.0, 54.0, 404.0, 232.0)
val afterFrame = Rect(584.0, 54.0, 404.0, 232.0)
val comparisonLines = listOf(
    beforeFrame.toFLine(filled = true, color = vec(0.08, 0.13, 0.18, 0.56)),
    afterFrame.toFLine(filled = true, color = vec(0.18, 0.11, 0.08, 0.54)),
    arrow(
        beforeFrame.right + 34.0, 170.0,
        afterFrame.x - 34.0, 170.0,
        color = vec(0.92, 0.90, 0.84, 0.95),
        thickness = 2.0
    ),
) +
    beforeLines.fitInto(beforeFrame, padding = 26.0) +
    afterLines.fitInto(afterFrame, padding = 26.0)

showInteractionOverview(
    graphicLines = comparisonLines,
    cards = listOf(
        beforeSummary,
        detail.summaryHtml("Duplicate event"),
        net.summaryHtml("After duplication", maxNodes = 5)
    ),
    channel = "overview",
    graphicTitle = "Before / rewrite / after geometry",
    graphicWidth = 1040.0,
    graphicHeight = 340.0
)

println("interaction duplication ready")
```

## ERASE starts a visible decay cascade

`erase-app` does not delete a subtree. It removes the `ERASE ⟂ APP` pair and puts a fresh `ERASE` agent on each of the application's two auxiliaries, each carrying half the application's energy attenuated to 0.55 with a small bias added back, or 0.39 from the application's 1.25. So one active pair becomes two, and the cascade in the "after" card is queued rather than finished: total energy is down from 4.00 to 2.64, and the next two steps are what actually consume the constant and the variable.

<!--
FIELD_TEST
id: docs.interaction-nets.erase
runner: backdoor-box
tags: [docs, interaction-nets, erase, decay]
timeoutSec: 45
withPlaywright: true
waitSec: 2.0
expect:
  outputContains:
    - "interaction erase ready"
  eventKinds:
    - set-output
  screenshot:
    kind: box
    padding: 12
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/interaction_nets_examples/erase.png" wait="2.0" capture="box" open-outputs="true" embed=true name="interaction-erase" w="1240" padding="12"}
:::static
import afield.Rect

val reducer = Reducer(Lambda.rules())
val net = Lambda.eraseApplicationDemo()

net.relax(20)
val beforeLines = net.toFLines()
val beforeSummary = net.summaryHtml("Before erase", maxNodes = 5)
val detail = reducer.stepDetailed(net)!!
net.relax(28)
val afterLines = net.toFLines()
val beforeFrame = Rect(52.0, 54.0, 404.0, 232.0)
val afterFrame = Rect(584.0, 54.0, 404.0, 232.0)
val comparisonLines = listOf(
    beforeFrame.toFLine(filled = true, color = vec(0.08, 0.13, 0.18, 0.56)),
    afterFrame.toFLine(filled = true, color = vec(0.18, 0.11, 0.08, 0.54)),
    arrow(
        beforeFrame.right + 34.0, 170.0,
        afterFrame.x - 34.0, 170.0,
        color = vec(0.92, 0.90, 0.84, 0.95),
        thickness = 2.0
    ),
) +
    beforeLines.fitInto(beforeFrame, padding = 26.0) +
    afterLines.fitInto(afterFrame, padding = 26.0)

showInteractionOverview(
    graphicLines = comparisonLines,
    cards = listOf(
        beforeSummary,
        detail.summaryHtml("Prune step"),
        net.summaryHtml("After erase step", maxNodes = 5)
    ),
    channel = "overview",
    graphicTitle = "Before / rewrite / after geometry",
    graphicWidth = 1040.0,
    graphicHeight = 340.0
)

println("interaction erase ready")
```
