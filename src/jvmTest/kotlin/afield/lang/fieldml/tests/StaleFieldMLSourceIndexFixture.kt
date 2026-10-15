package afield.lang.fieldml.tests

import afield.ai.knowledge.FilestoreDir
import afield.runtime.completion.KotlinDefinitionRecord
import afield.runtime.completion.KotlinSourceFileRecord
import afield.runtime.completion.KotlinSourceIndex
import afield.runtime.completion.KotlinSourceIndexDb
import java.nio.file.Files

internal fun <T> withStaleFieldMLSourceIndex(block: () -> T): T {
    val previous = KotlinSourceIndex.latestIndex
    val dir = Files.createTempDirectory("fieldml-stale-layout")
    try {
        return FilestoreDir.withDirectory(dir) {
            val removed = "src/jvmMain/kotlin/afield/graphics/someSkiko.kt"
            // A surviving entry prevents the old all-or-nothing empty-result fallback.
            val surviving = "src/jvmMain/kotlin/afield/ui/visualization/plotly.kt"
            check(Files.isRegularFile(java.nio.file.Path.of(surviving)))
            KotlinSourceIndex.latestIndex = KotlinSourceIndexDb(
                root = ".", apiKeyHash = null, updatedAtEpochMs = 0,
                files = listOf(removed, surviving).map { KotlinSourceFileRecord(it, "", null, 0, 0) },
                definitions = listOf(removed, surviving).map {
                    KotlinDefinitionRecord("lines", "fun", it, 1, null, "")
                },
            )
            block()
        }
    } finally {
        KotlinSourceIndex.latestIndex = previous
        dir.toFile().deleteRecursively()
    }
}
