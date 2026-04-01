# Interaction Nets Examples

This page uses the same four scenarios the interaction-net smoke suite cares about. The point is not just to prove the rules reduce; it is to show what those reductions look like when they go through the native FLine renderer and land in ordinary output disclosures.

## Beta bridge with swapped pair orientation

The first check is the subtle one: `APP ⟂ LAM` should still reduce correctly even when the active pair was connected in the opposite insertion order. The left frame is the net before the step, the center card describes the rewrite that fired, and the right frame is the relaxed net after that single reduction.

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
import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.min

fun panel(x: Double, y: Double, w: Double, h: Double, color: Vec4) = FLine().apply {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
    filled = true
    stroked = true
    thickness = 1.0
    this.color = color
}

fun arrow(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().apply {
    moveTo(x0, y0)
    lineTo(x1, y1)
    stroked = true
    filled = false
    pointed = true
    pointSize = 10.0
    thickness = 2.0
    color = vec(0.92, 0.90, 0.84, 0.95)
}

fun fitInto(frame: Rect, lines: List<FLine>, padding: Double = 26.0): List<FLine> {
    val bounds = lines.mapNotNull { runCatching { it.fast2DBounds() }.getOrNull() }
        .reduceOrNull { a, b -> a.union(b) } ?: return lines
    val availableW = (frame.w - padding * 2.0).coerceAtLeast(1.0)
    val availableH = (frame.h - padding * 2.0).coerceAtLeast(1.0)
    val scale = min(
        availableW / bounds.w.coerceAtLeast(1.0),
        availableH / bounds.h.coerceAtLeast(1.0)
    )
    val offsetX = frame.x + padding - bounds.x * scale + (availableW - bounds.w * scale) * 0.5
    val offsetY = frame.y + padding - bounds.y * scale + (availableH - bounds.h * scale) * 0.5
    return lines.map { (it * scale) + vec(offsetX, offsetY, 0.0) }
}

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
    panel(beforeFrame.x, beforeFrame.y, beforeFrame.w, beforeFrame.h, vec(0.08, 0.13, 0.18, 0.56)),
    panel(afterFrame.x, afterFrame.y, afterFrame.w, afterFrame.h, vec(0.18, 0.11, 0.08, 0.54)),
    arrow(beforeFrame.right + 34.0, 170.0, afterFrame.x - 34.0, 170.0),
) +
    fitInto(beforeFrame, beforeLines) +
    fitInto(afterFrame, afterLines)

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

The reducer now has a real pair selector, so evaluation order can be part of the behavior instead of a hidden detail. The left frame shows the two candidate pairs before selection; the center card identifies the hotter pair that fired; the right frame shows the net after one reduction, with the colder pair still present.

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
import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.min

fun panel(x: Double, y: Double, w: Double, h: Double, color: Vec4) = FLine().apply {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
    filled = true
    stroked = true
    thickness = 1.0
    this.color = color
}

fun arrow(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().apply {
    moveTo(x0, y0)
    lineTo(x1, y1)
    stroked = true
    filled = false
    pointed = true
    pointSize = 10.0
    thickness = 2.0
    color = vec(0.92, 0.90, 0.84, 0.95)
}

fun fitInto(frame: Rect, lines: List<FLine>, padding: Double = 26.0): List<FLine> {
    val bounds = lines.mapNotNull { runCatching { it.fast2DBounds() }.getOrNull() }
        .reduceOrNull { a, b -> a.union(b) } ?: return lines
    val availableW = (frame.w - padding * 2.0).coerceAtLeast(1.0)
    val availableH = (frame.h - padding * 2.0).coerceAtLeast(1.0)
    val scale = min(
        availableW / bounds.w.coerceAtLeast(1.0),
        availableH / bounds.h.coerceAtLeast(1.0)
    )
    val offsetX = frame.x + padding - bounds.x * scale + (availableW - bounds.w * scale) * 0.5
    val offsetY = frame.y + padding - bounds.y * scale + (availableH - bounds.h * scale) * 0.5
    return lines.map { (it * scale) + vec(offsetX, offsetY, 0.0) }
}

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
    panel(beforeFrame.x, beforeFrame.y, beforeFrame.w, beforeFrame.h, vec(0.08, 0.13, 0.18, 0.56)),
    panel(afterFrame.x, afterFrame.y, afterFrame.w, afterFrame.h, vec(0.18, 0.11, 0.08, 0.54)),
    arrow(beforeFrame.right + 34.0, 170.0, afterFrame.x - 34.0, 170.0),
) +
    fitInto(beforeFrame, beforeLines) +
    fitInto(afterFrame, afterLines)

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

The duplication rule is no longer just a topological trick. The left frame shows the pre-step net, the middle card records the duplication event, and the right frame shows the two descendants after the state split and relaxation.

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
import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.min

fun panel(x: Double, y: Double, w: Double, h: Double, color: Vec4) = FLine().apply {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
    filled = true
    stroked = true
    thickness = 1.0
    this.color = color
}

fun arrow(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().apply {
    moveTo(x0, y0)
    lineTo(x1, y1)
    stroked = true
    filled = false
    pointed = true
    pointSize = 10.0
    thickness = 2.0
    color = vec(0.92, 0.90, 0.84, 0.95)
}

fun fitInto(frame: Rect, lines: List<FLine>, padding: Double = 26.0): List<FLine> {
    val bounds = lines.mapNotNull { runCatching { it.fast2DBounds() }.getOrNull() }
        .reduceOrNull { a, b -> a.union(b) } ?: return lines
    val availableW = (frame.w - padding * 2.0).coerceAtLeast(1.0)
    val availableH = (frame.h - padding * 2.0).coerceAtLeast(1.0)
    val scale = min(
        availableW / bounds.w.coerceAtLeast(1.0),
        availableH / bounds.h.coerceAtLeast(1.0)
    )
    val offsetX = frame.x + padding - bounds.x * scale + (availableW - bounds.w * scale) * 0.5
    val offsetY = frame.y + padding - bounds.y * scale + (availableH - bounds.h * scale) * 0.5
    return lines.map { (it * scale) + vec(offsetX, offsetY, 0.0) }
}

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
    panel(beforeFrame.x, beforeFrame.y, beforeFrame.w, beforeFrame.h, vec(0.08, 0.13, 0.18, 0.56)),
    panel(afterFrame.x, afterFrame.y, afterFrame.w, afterFrame.h, vec(0.18, 0.11, 0.08, 0.54)),
    arrow(beforeFrame.right + 34.0, 170.0, afterFrame.x - 34.0, 170.0),
) +
    fitInto(beforeFrame, beforeLines) +
    fitInto(afterFrame, afterLines)

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

`ERASE` is more interesting as a slow sink than as an invisible delete key. The left frame is the net before the prune step, the middle card explains the interaction that fired, and the right frame shows the first visible decay cascade after the step.

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
import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.min

fun panel(x: Double, y: Double, w: Double, h: Double, color: Vec4) = FLine().apply {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
    filled = true
    stroked = true
    thickness = 1.0
    this.color = color
}

fun arrow(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().apply {
    moveTo(x0, y0)
    lineTo(x1, y1)
    stroked = true
    filled = false
    pointed = true
    pointSize = 10.0
    thickness = 2.0
    color = vec(0.92, 0.90, 0.84, 0.95)
}

fun fitInto(frame: Rect, lines: List<FLine>, padding: Double = 26.0): List<FLine> {
    val bounds = lines.mapNotNull { runCatching { it.fast2DBounds() }.getOrNull() }
        .reduceOrNull { a, b -> a.union(b) } ?: return lines
    val availableW = (frame.w - padding * 2.0).coerceAtLeast(1.0)
    val availableH = (frame.h - padding * 2.0).coerceAtLeast(1.0)
    val scale = min(
        availableW / bounds.w.coerceAtLeast(1.0),
        availableH / bounds.h.coerceAtLeast(1.0)
    )
    val offsetX = frame.x + padding - bounds.x * scale + (availableW - bounds.w * scale) * 0.5
    val offsetY = frame.y + padding - bounds.y * scale + (availableH - bounds.h * scale) * 0.5
    return lines.map { (it * scale) + vec(offsetX, offsetY, 0.0) }
}

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
    panel(beforeFrame.x, beforeFrame.y, beforeFrame.w, beforeFrame.h, vec(0.08, 0.13, 0.18, 0.56)),
    panel(afterFrame.x, afterFrame.y, afterFrame.w, afterFrame.h, vec(0.18, 0.11, 0.08, 0.54)),
    arrow(beforeFrame.right + 34.0, 170.0, afterFrame.x - 34.0, 170.0),
) +
    fitInto(beforeFrame, beforeLines) +
    fitInto(afterFrame, afterLines)

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
