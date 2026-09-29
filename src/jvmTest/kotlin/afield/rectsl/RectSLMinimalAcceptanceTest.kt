package afield.rectsl

import kotlin.test.Test

/**
 * [RectSLSmokeTest]'s minimal acceptance program, run as a test so it cannot rot again. It predated the
 * `->` mutator spelling and had stopped compiling — nothing ran its `main`, so nothing noticed.
 */
class RectSLMinimalAcceptanceTest {
    @Test
    fun theMinimalAcceptanceProgramCompilesSamplesAndLowers() {
        RectSLSmokeTest.main(emptyArray())
    }
}
