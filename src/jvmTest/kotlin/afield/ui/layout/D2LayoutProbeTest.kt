package afield.ui.layout

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A helper that appears (or is rebuilt) under a running Field is noticed without a reset. */
class D2LayoutProbeTest {
    @Test
    fun aHelperBuiltAfterTheFirstProbeIsPickedUp() {
        val dir = kotlin.io.path.createTempDirectory("d2-probe").toFile()
        val bin = File(dir, "field-d2-layout")
        val previous = D2Layout.binaryOverride
        try {
            D2Layout.binaryOverride = bin.path
            D2Layout.reset()
            assertNull(D2Layout.version, "nothing built yet")

            bin.writeText("#!/bin/sh\ncat >/dev/null\necho '{\"protocol\":1,\"ok\":true,\"d2Version\":\"fake-1\"}'\n")
            bin.setExecutable(true)
            assertEquals("fake-1", D2Layout.version, "the newly built helper is probed")

            bin.writeText("#!/bin/sh\ncat >/dev/null\necho '{\"protocol\":1,\"ok\":true,\"d2Version\":\"fake-2\"}'\n")
            bin.setLastModified(bin.lastModified() + 2_000)
            assertEquals("fake-2", D2Layout.version, "and a rebuilt one is probed again")
        } finally {
            D2Layout.binaryOverride = previous
            D2Layout.reset()
            dir.deleteRecursively()
        }
    }
}
