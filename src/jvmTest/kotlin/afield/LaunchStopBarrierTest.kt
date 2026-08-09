package afield

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchStopBarrierTest {
    @Test
    fun stopInvalidatesEveryStrandAndCancelsRegisteredJobs() {
        val box = Box("stop-barrier")
        box.runGeneration["default"] = 4
        box.runGeneration["alternate"] = 9
        box.currentRunningStates["default"] = RunningState(4, "launch")
        box.currentRunningStates["alternate"] = RunningState(9, "launch")
        box.currentRunningStates["pending-only"] = RunningState(12, "launching")
        val active = Job()
        box.jobs.add(active)

        KotlinExecution().stop(box)

        assertEquals(5, box.runGeneration["default"])
        assertEquals(10, box.runGeneration["alternate"])
        assertEquals(13, box.runGeneration["pending-only"])
        assertTrue(active.isCancelled)
        assertTrue(box.jobs.isEmpty())
        assertTrue(box.currentRunningStates.isEmpty())
    }

    @Test
    fun lateJobCannotRegisterAcrossStop() {
        val box = Box("late-job")
        val launchEpoch = box.captureLaunchEpoch()

        box.stopTrackedJobs()
        val late = Job()

        assertFalse(box.registerLaunchJob(late, launchEpoch))
        assertTrue(late.isCancelled)
        assertTrue(box.jobs.isEmpty())
        assertTrue(box.currentRunningStates.isEmpty())
    }

    @Test
    fun preparedReturnValueCannotDispatchAcrossStop() {
        val box = Box("late-return")
        val launchEpoch = box.captureLaunchEpoch()
        val notifications = mutableListOf<Boolean>()

        box.stopTrackedJobs()
        val consumed = ReturnValueRegistry.launchPrepared(
            box,
            Any(),
            "default",
            launchEpoch,
        ) { running, _, _ -> notifications += running }

        assertFalse(consumed)
        assertTrue(notifications.isEmpty())
        assertTrue(box.jobs.isEmpty())
    }
}
