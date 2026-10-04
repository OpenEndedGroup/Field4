package afield

import afield.ai.core.completion.Ai4CompletionDoc
import afield.ai.core.completion.GENERATED_COMPLETION_DOCS_DECLINED_FILE
import afield.ai.core.completion.GeneratedCompletionDocCache
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Completion docs are generated and polished only for declarations in this repository's own tree. */
class CompletionDocTreeScopeTest {
    @Test
    fun `our source sets are in the tree, personal included`() {
        assertTrue(Ai4CompletionDoc.isInOurTree("src/jvmMain/kotlin/afield/four/util/KeyCamera.kt"))
        assertTrue(Ai4CompletionDoc.isInOurTree("src/" + "personal/kotlin/Example.kt"))
    }

    @Test
    fun `dependency sources and escapes are not`() {
        assertFalse(Ai4CompletionDoc.isInOurTree(
            "jar:file:///Users/x/.gradle/caches/dataframe-core-1.0.0-Beta2-sources.jar!/org/jetbrains/kotlinx/dataframe/impl/DataFrameImpl.kt"
        ))
        assertFalse(Ai4CompletionDoc.isInOurTree("/Users/x/field4/src/jvmMain/kotlin/afield/Boxes.kt"))
        assertFalse(Ai4CompletionDoc.isInOurTree("src/../build/generated/Foo.kt"))
        assertFalse(Ai4CompletionDoc.isInOurTree(""))
    }

    @Test
    fun `a draft the docs pass declined is neither shown nor regenerated`() {
        val dir = Files.createTempDirectory("completion-doc-declined-")
        val cacheFile = dir.resolve("generated_completion_docs.json")
        val cache = GeneratedCompletionDocCache(cacheFile)
        cache.put("afield.shift", "src/jvmMain/kotlin/afield/Foo.kt", 9, "hash", "Adds one.", "fast")
        assertFalse(cache.isDeclined("afield.shift", "src/jvmMain/kotlin/afield/Foo.kt", 9, "hash"))

        // The key as docs_notes.py reads it: the record's name in the cache file.
        val key = Regex("\"([0-9a-f]{64})\"").find(Files.readString(cacheFile))!!.groupValues[1]
        Files.writeString(dir.resolve(GENERATED_COMPLETION_DOCS_DECLINED_FILE), """{"version": 1, "keys": ["$key"]}""")
        assertTrue(cache.isDeclined("afield.shift", "src/jvmMain/kotlin/afield/Foo.kt", 9, "hash"))
        assertFalse(cache.isDeclined("afield.shift", "src/jvmMain/kotlin/afield/Foo.kt", 9, "edited"), "a changed declaration is a new draft")
    }
}
