package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `AgentJvmMainLauncher` refuses to start when the JVM lacks what `requiredAgentJvmArgs()` passes,
 * instead of failing later somewhere unrelated. The probe is data so the decision is testable
 * without forking a JVM; the live half (a real `run-jvm-main` passes, a hand-built launch line is
 * refused with exit 4) was checked through the backdoor when this landed.
 */
class AgentJvmMainLauncherRequirementsTest {

    private val complete = AgentJvmRequirementProbe(
        javaNetOpen = true,
        nativeAccessEnabled = true,
        vectorModuleResolved = true,
        lwjglStackSizeSet = true,
        headlessAwt = true,
        startOnFirstThread = true,
    )

    @Test
    fun aCompleteJvmIsAccepted() {
        assertEquals(emptyList(), missingAgentJvmRequirements(complete))
        assertEquals(emptyList(), missingAgentJvmRequirements(complete.copy(startOnFirstThread = null)))
    }

    @Test
    fun eachMissingRequirementIsNamedAsTheArgThatSuppliesIt() {
        assertEquals(
            listOf("--add-opens=java.base/java.net=ALL-UNNAMED"),
            missingAgentJvmRequirements(complete.copy(javaNetOpen = false)),
        )
        assertEquals(
            listOf("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread"),
            missingAgentJvmRequirements(complete.copy(nativeAccessEnabled = false, startOnFirstThread = false)),
        )
        val bare = AgentJvmRequirementProbe(false, false, false, false, false, false)
        assertEquals(6, missingAgentJvmRequirements(bare).size)
    }

    @Test
    fun theRefusalPointsAtRunJvmMain() {
        val message = agentJvmRequirementsMessage("afield.examples.X", listOf("--enable-native-access=ALL-UNNAMED"))
        assertTrue("run-jvm-main afield.examples.X" in message, message)
        assertTrue(SKIP_ARG_CHECK_ENV in message, message)
    }
}
