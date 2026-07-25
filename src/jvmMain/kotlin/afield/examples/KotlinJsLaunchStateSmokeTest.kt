package afield.examples

import afield.Box
import afield.RunningState
import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinJsHeadResponse
import afield.remotejs.KotlinJsLaunchTracker
import afield.remotejs.kotlinJsLifecycleName

/*
FIELD_TEST
id: remotejs.kotlinjs-launch-state
runner: jvm-main
entrypoint: afield.examples.KotlinJsLaunchStateSmokeTest
tags: [smoke, remotejs, kotlinjs, lifecycle]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsLaunchStateSmokeTest ok"
*/

/** Pure JVM coverage for multi-head terminal aggregation and per-strand lifecycle identity. */
object KotlinJsLaunchStateSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val box = Box("kotlinjs-launch-state")
        val selection = KotlinJsHeadProfiles.resolve("// {{ remote }}\nonStart {}")
        val stopped = mutableListOf<String>()
        var finished = 0

        box.runGeneration["music"] = 4
        box.currentRunningStates["music"] = RunningState(4, "launch")
        val tracker = KotlinJsLaunchTracker(
            box = box,
            strand = "music",
            generation = 4,
            lifecycleName = kotlinJsLifecycleName(box, "music"),
            selection = selection,
            notifyStopped = { stopped += "music" },
            onFinished = { finished += 1 },
        )

        val expected = setOf("head-a", "head-b")
        require(tracker.onResponse(KotlinJsHeadResponse("SUCCESS", "head-a", expected))) {
            "output printed during onStart is not the terminal evaluation response"
        }
        require(tracker.onResponse(KotlinJsHeadResponse("RUN-START", "head-a", expected)))
        require(tracker.onResponse(KotlinJsHeadResponse("SUCCESS", "head-a", expected, terminal = true)))
        require(tracker.onResponse(KotlinJsHeadResponse("SUCCESS", "head-b", expected, terminal = true))) {
            "one still-active head must keep the response handler alive"
        }
        require(!tracker.onResponse(KotlinJsHeadResponse("RUN-STOP", "head-a", expected)))
        require(stopped == listOf("music") && finished == 1)
        require("music" !in box.currentRunningStates && box.runGeneration["music"] == 5)

        box.runGeneration["plain"] = 2
        box.currentRunningStates["plain"] = RunningState(2, "launch")
        val plain = KotlinJsLaunchTracker(
            box = box,
            strand = "plain",
            generation = 2,
            lifecycleName = kotlinJsLifecycleName(box, "plain"),
            selection = selection,
            notifyStopped = { stopped += "plain" },
            onFinished = { finished += 1 },
        )
        require(!plain.onResponse(KotlinJsHeadResponse("SUCCESS", "head-a", setOf("head-a"), terminal = true))) {
            "Launch code that installs no lifecycle must stop after successful evaluation"
        }

        box.runGeneration["durable"] = 7
        box.currentRunningStates["durable"] = RunningState(7, "launch")
        val durable = KotlinJsLaunchTracker(
            box = box,
            strand = "durable",
            generation = 7,
            lifecycleName = kotlinJsLifecycleName(box, "durable"),
            selection = selection,
            notifyStopped = { stopped += "durable" },
            onFinished = { finished += 1 },
        )
        durable.deliveryFinished(sent = true, durableWithoutResponse = true)
        require("durable" in box.currentRunningStates)
        durable.cancel()
        durable.cancel()
        require(stopped.count { it == "durable" } == 1) { "Stop must be idempotent" }

        var staleSends = 0
        require(!durable.sendIfActive {
            staleSends += 1
            true
        })
        require(staleSends == 0) {
            "A stopped in-flight compile must not publish its JavaScript afterward"
        }

        require(kotlinJsLifecycleName(box, "music") != kotlinJsLifecycleName(box, "visuals")) {
            "named strands must not overwrite the same remote task"
        }
        println("KotlinJsLaunchStateSmokeTest ok")
    }
}
