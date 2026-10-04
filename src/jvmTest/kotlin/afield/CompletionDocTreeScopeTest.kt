package afield

import afield.ai.core.completion.Ai4CompletionDoc
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Completion docs are generated and polished only for declarations in this repository's own tree. */
class CompletionDocTreeScopeTest {
    @Test
    fun `our source sets are in the tree, personal included`() {
        assertTrue(Ai4CompletionDoc.isInOurTree("src/jvmMain/kotlin/afield/four/util/KeyCamera.kt"))
        assertTrue(Ai4CompletionDoc.isInOurTree("src/personal/kotlin/sentences/Layout.kt"))
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
}
