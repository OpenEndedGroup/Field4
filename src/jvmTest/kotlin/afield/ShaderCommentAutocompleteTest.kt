package afield

import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import afield.four.modules.parseGlslMemberComments
import afield.macros.gatherPreambleUpwards
import afield.stdlib.applyInsertToPreamble
import afield.stdlib.insertForShader
import afield.stdlib.normalizePreambleText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journey of a `// note` written at the end of a shader's push-constant line: scraped from the GLSL
 * source, appended to every generated Kotlin accessor for that member, and read back off the preamble as
 * completion documentation.
 *
 * Reflection throws comments away, so each leg is tested where it can be tested without a GPU: the scrape
 * and the read-back are pure string work, and the middle leg needs only shaderc + SPIRV-Cross, which run
 * headless.
 */
class ShaderCommentAutocompleteTest {

    private val vertexSource = """
        #version 450
        layout(push_constant) uniform Push {
            float time;   // seconds since the box started
            vec4 tint;    // rgba multiplied into the output
        } pc;
        layout (location = 0) in vec3 pos;
        void main() { gl_Position = vec4(pos, 1) * pc.time * pc.tint.w; }
    """.trimIndent()

    private val fragmentSource = """
        #version 450
        layout(push_constant) uniform Push {
            float time;
            vec4 tint;
        } pc;
        layout (location = 0) out vec4 col;
        void main() { col = pc.tint * pc.time; }
    """.trimIndent()

    @Test
    fun scrapesTrailingCommentsFromAPushConstantBlock() {
        val comments = parseGlslMemberComments(vertexSource)

        assertEquals("seconds since the box started", comments["Push.time"])
        assertEquals("rgba multiplied into the output", comments["Push.tint"])
        // The block's instance name is an alias, because reflection may name the block either way.
        assertEquals("seconds since the box started", comments["pc.time"])
        // And the bare name, for a caller that has no block to qualify with.
        assertEquals("seconds since the box started", comments["time"])
    }

    @Test
    fun scrapesStructMembersAndIgnoresNonDeclarationComments() {
        val comments = parseGlslMemberComments(
            """
            #version 450
            // a file-level comment, attached to nothing
            #define WIBBLE 4 // a preprocessor line is not a declaration
            struct Params {
                vec4 kali0;   // fold amount
                vec4 kali1;
            };
            /* a block comment
               containing // something that looks like a trailing comment */
            layout(push_constant) uniform Push {
                Params p;   // the whole parameter block
            } pc;
            void main() {
                float scratch;   // a local, not a binding
            }
            """.trimIndent()
        )

        assertEquals("fold amount", comments["Params.kali0"])
        assertEquals("the whole parameter block", comments["Push.p"])
        assertNull(comments["scratch"], "a function-local declaration is not a shader binding: $comments")
        assertNull(comments["kali1"])
        assertNull(comments["WIBBLE"])
        assertTrue(comments.keys.none { it.endsWith("something") }, comments.toString())
    }

    @Test
    fun aBareNameSharedByTwoBlocksWithDifferentCommentsIsDropped() {
        val comments = parseGlslMemberComments(
            """
            #version 450
            struct A { float amount;   // how much of A
            };
            struct B { float amount;   // how much of B
            };
            struct C { float shared;   // the same note
            };
            struct D { float shared;   // the same note
            };
            """.trimIndent()
        )

        assertEquals("how much of A", comments["A.amount"])
        assertEquals("how much of B", comments["B.amount"])
        assertNull(comments["amount"], "an ambiguous bare name must not be asserted: $comments")
        // Two blocks agreeing is not ambiguity.
        assertEquals("the same note", comments["shared"])
    }

    @Test
    fun theGeneratedAccessorsCarryTheComment() {
        val vi = Introspection(vertexSource, ShaderKind.VERTEX_SHADER)
        val fi = Introspection(fragmentSource, ShaderKind.FRAGMENT_SHADER)
        assertTrue(vi.valid, "vertex shader did not compile")
        assertTrue(fi.valid, "fragment shader did not compile")

        val preamble = insertForShader(vi, fi, args = listOf("()")).preambleAfterImports

        val meshLine = lineDeclaring(preamble, "var Mesh.time")
        assertTrue(meshLine.endsWith("// seconds since the box started"), meshLine)
        // The line's identity for preamble merging is the marker, which has to stay at the front.
        assertTrue(meshLine.startsWith("/*graphics-pc-binding:time*/"), meshLine)

        listOf("var Structure.time", "var MeshGroup.time").forEach {
            assertTrue(lineDeclaring(preamble, it).endsWith("// seconds since the box started"), preamble)
        }
        assertTrue(lineDeclaring(preamble, "var Mesh.tint").endsWith("// rgba multiplied into the output"), preamble)

        // A trailing comment must not disturb the line-oriented preamble merge: keys still dedupe.
        val normalized = normalizePreambleText("$preamble\n$preamble")
        assertEquals(
            1,
            normalized.lines().count { it.contains("var Mesh.time") },
            "a commented generated line should still dedupe by its marker"
        )
    }

    @Test
    fun completionReadsTheCommentBackOffThePreamble() {
        val preamble = """
            /*graphics-pc-binding:time*/ var Mesh.time by Mesh.BoundFromStructure<Float>() // seconds since the box started
            /*graphics-pc-binding-group:time*/ var MeshGroup.time by MeshGroup.BoundFromStructure<Float>() // seconds since the box started
            /*graphics-pc-binding:tint*/ var Mesh.tint by Mesh.BoundFromStructure<Vector4f>() // rgba multiplied into the output
            /*graphics-pc-binding:plain*/ var Mesh.plain by Mesh.BoundFromStructure<Float>()
            /*start*/
            /*graphics-structure-type:Params:Params*/
            class Params(
                members: List<Introspected.Member> = ParamsLayoutMembers()
            ) : Structure(members) {
                var kali0 by Structure.FromStructure<Vector4f>() // fold amount
            }
            /*end*/
        """.trimIndent()
        val docs = ShaderBindingDocs.forPreamble(preamble)

        assertEquals(
            "seconds since the box started",
            docs.documentationFor("time", "afield.four.modules.Mesh")
        )
        // When the backend names no receiver at all, the unambiguous bare name answers.
        assertEquals(
            "rgba multiplied into the output",
            docs.documentationFor("tint", null)
        )
        // A generated structure class documents its own members, keyed by the class.
        assertEquals(
            "fold amount",
            docs.documentationFor("kali0", "Params")
        )
        assertNull(docs.documentationFor("plain", "Mesh"))
        assertNull(docs.documentationFor("notInThePreamble", "Mesh"))
    }

    @Test
    fun aReceiverTheCommentIsNotAboutGetsNoDocumentation() {
        // `time` is documented for Mesh only; a same-named member of another type must not inherit it.
        val preamble = """
            /*graphics-pc-binding:time*/ var Mesh.time by Mesh.BoundFromStructure<Float>() // seconds since the box started
            /*supermap*/ var Other.time by Other.Something<Float>()
        """.trimIndent()
        val docs = ShaderBindingDocs.forPreamble(preamble)

        assertEquals(
            "seconds since the box started",
            docs.documentationFor("time", "Mesh")
        )
        assertNull(docs.documentationFor("time", "Other"))
    }

    @Test
    fun theNoteSurvivesTheRealPreambleMergeAndTheBoxWalk() {
        // The two integration points between "generated" and "read back": `applyInsertToPreamble`, which
        // merges the line into the box segment by segment, and `gatherPreambleUpwards`, which is the text
        // completion actually resolves against.
        val vi = Introspection(vertexSource, ShaderKind.VERTEX_SHADER)
        val fi = Introspection(fragmentSource, ShaderKind.FRAGMENT_SHADER)
        assertTrue(vi.valid && fi.valid, "shaders did not compile")

        val box = Box("shader-comment-test")
        applyInsertToPreamble(box, insertForShader(vi, fi, args = listOf("()")))
        // Twice, as a reload does — the second insert must replace the first, not double the line.
        applyInsertToPreamble(box, insertForShader(vi, fi, args = listOf("()")))

        val docs = ShaderBindingDocs.indexFor(box)
        assertEquals("seconds since the box started", docs.documentationFor("time", "Mesh"))
        assertEquals("rgba multiplied into the output", docs.documentationFor("tint", "afield.four.modules.Mesh"))
        assertEquals("seconds since the box started", docs.documentationFor("time", "MeshGroup"))
        assertNull(docs.documentationFor("time", "String"))
        assertEquals(
            1,
            box.preamble.lines().count { it.contains("var Mesh.time") },
            "a reload should replace the commented line, not append a second copy"
        )
    }

    @Test
    fun aBoxWithNoPreambleOfItsOwnStillOnlyInheritsMarkedLines() {
        // The preamble gather takes the STARTING box's preamble whole and filters every ancestor's down
        // to imports plus the inherit marker. A box whose `preamble` has never been written is still the
        // starting box: it contributes nothing, and its parent is still an ancestor.
        //
        // It used to escape the gate. `searchUpwards` reads the property with `getPropertyRaw`, which
        // answers null for a property nothing has written, and the callback returned on null BEFORE
        // clearing its first-box flag — so the first ancestor that had a preamble was treated as the
        // starting box and its whole preamble came down. Whether a box got that or the gate turned on
        // whether anything had happened to read `Box.preamble` first, because the `Delegation` default
        // writes "" back on a bare read.
        val parent = Box("preamble-gate-parent")
        parent.setPropertyRaw(
            "preamble",
            """
            import java.nio.*
            var Box.shared by Inheritance<Int>() /**/
            /*graphics-pc-binding:time*/ var Mesh.time by Mesh.BoundFromStructure<Float>()
            """.trimIndent()
        )

        val unwritten = Box("preamble-gate-child-unwritten")
        unwritten.parents.add(parent)
        assertNull(unwritten.getPropertyRaw("preamble"), "the child's preamble must start out unwritten")

        val written = Box("preamble-gate-child-written")
        written.parents.add(parent)
        written.setPropertyRaw("preamble", "")

        listOf("never written" to unwritten, "written empty" to written).forEach { (state, child) ->
            val gathered = gatherPreambleUpwards(child)
            assertTrue("import java.nio.*" in gathered, "$state: an ancestor's imports always come down")
            assertTrue("var Box.shared" in gathered, "$state: a marked ancestor line comes down")
            assertFalse(
                "var Mesh.time" in gathered,
                "$state: an unmarked ancestor line must not come down:\n$gathered"
            )
        }
    }

    @Test
    fun aGeneratedShaderBindingDoesNotCrossAConnectEdge() {
        // `moduleHere`'s generated accessors are keyed by a LEADING marker comment (for preamble merging)
        // and carry no trailing inherit marker, so they stay in the box whose shader declared them —
        // including the trailing note, which would otherwise document a child's identically-named row
        // with a comment written about a different shader.
        val vi = Introspection(vertexSource, ShaderKind.VERTEX_SHADER)
        val fi = Introspection(fragmentSource, ShaderKind.FRAGMENT_SHADER)
        assertTrue(vi.valid && fi.valid, "shaders did not compile")

        val shaderBox = Box("shader-binding-parent")
        applyInsertToPreamble(shaderBox, insertForShader(vi, fi, args = listOf("()")))
        assertEquals(
            "seconds since the box started",
            ShaderBindingDocs.indexFor(shaderBox).documentationFor("time", "Mesh"),
            "the declaring box documents its own binding"
        )

        val child = Box("shader-binding-child")
        child.parents.add(shaderBox)
        val gathered = gatherPreambleUpwards(child)
        assertFalse("var Mesh.time" in gathered, "generated bindings must not cross a connect edge:\n$gathered")
        assertNull(
            ShaderBindingDocs.indexFor(child).documentationFor("time", "Mesh"),
            "a child must not inherit documentation for a binding it does not have"
        )
    }

    private fun lineDeclaring(preamble: String, declaration: String): String =
        preamble.lines().map { it.trim() }.firstOrNull { it.contains(declaration) }
            ?: error("no line declaring '$declaration' in:\n$preamble")
}
