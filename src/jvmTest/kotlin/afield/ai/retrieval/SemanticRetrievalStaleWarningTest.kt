package afield.ai.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticRetrievalStaleWarningTest {
    @Test fun aStaleIndexLeadsWithAWarningThatPointsAtLiteralSearch() {
        val warning = SemanticRetrievalService.staleWarning(SemanticRetrievalStatus(enabled = true, indexPath = "", indexExists = true, indexStale = true, lastBuildEpochMs = 1_793_127_416_363))
        assertTrue(warning.startsWith("WARNING"), warning)
        assertTrue("not evidence" in warning && "FIELD_DOCS" in warning && "2026-" in warning, warning)
        assertEquals("", SemanticRetrievalService.staleWarning(SemanticRetrievalStatus(enabled = true, indexPath = "", indexExists = true, indexStale = false)))
    }
}
