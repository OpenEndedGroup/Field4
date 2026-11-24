package afield.graphics.vulkan.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VkPhysicalDeviceCooperativeMatrixFeaturesKHR
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The device `pNext` chain checks that keep a stale optional NR adapter from breaking every
 * renderer: an adapter built before its source learned to share Field's cooperative-matrix struct
 * appended a second one, and vkCreateDevice refused the whole chain.
 */
class FeatureChainTest {
    @Test
    fun findsTheTailAndNoDuplicateInAValidChain() = MemoryStack.stackPush().use { stack ->
        val head = VkPhysicalDeviceFeatures2.calloc(stack).`sType$Default`()
        val v11 = VkPhysicalDeviceVulkan11Features.calloc(stack).`sType$Default`()
        val v13 = VkPhysicalDeviceVulkan13Features.calloc(stack).`sType$Default`()
        val coop = VkPhysicalDeviceCooperativeMatrixFeaturesKHR.calloc(stack).`sType$Default`()
        head.pNext(v11.address()); v11.pNext(v13.address()); v13.pNext(coop.address())
        assertEquals(coop.address(), featureChainTail(head.address()))
        assertNull(duplicateFeatureStructType(head.address()))
    }

    @Test
    fun reportsASecondCooperativeMatrixStructAndCuttingRestoresTheChain() = MemoryStack.stackPush().use { stack ->
        val head = VkPhysicalDeviceFeatures2.calloc(stack).`sType$Default`()
        val coop = VkPhysicalDeviceCooperativeMatrixFeaturesKHR.calloc(stack).`sType$Default`()
        head.pNext(coop.address())
        val fieldTail = featureChainTail(head.address())
        // What the stale adapter did: append its own copy after Field's.
        val second = VkPhysicalDeviceCooperativeMatrixFeaturesKHR.calloc(stack).`sType$Default`()
        coop.pNext(second.address())
        assertEquals(coop.sType(), duplicateFeatureStructType(head.address()))
        org.lwjgl.system.MemoryUtil.memPutAddress(fieldTail + org.lwjgl.vulkan.VkBaseOutStructure.PNEXT, 0L)
        assertNull(duplicateFeatureStructType(head.address()))
        assertEquals(coop.address(), featureChainTail(head.address()))
    }
}
