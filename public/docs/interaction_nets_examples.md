# Interaction Nets examples

These are the four scenarios covered by the interaction-net smoke suite. The examples show reducer steps as ordinary Field output: before geometry, rewrite summary, and after geometry.

## Beta bridge with swapped pair orientation

The first check is the subtle one: `APP ⟂ LAM` should reduce correctly even when the active pair was connected in the opposite insertion order. The left frame is the net before the step, the center card describes the rewrite, and the right frame is the relaxed net after that single reduction.

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

The reducer has a pair selector, so evaluation order can be visible behavior. The left frame shows two candidate pairs; the center card identifies the hotter pair that fired; the right frame shows the net after one reduction, with the colder pair still present.

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

The duplication rule also carries state. The left frame shows the pre-step net, the middle card records the duplication event, and the right frame shows the two descendants after state split and relaxation.

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

`ERASE` works better here as a slow sink than as an invisible delete key. The left frame is the net before the prune step, the middle card explains the interaction, and the right frame shows the first visible decay cascade after the step.

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
