package afield.ml.tests

import afield.Completion
import afield.CompletionRequest
import afield.SymbolContextRequest
import afield.ml.FieldMLCompletionEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldMLCompletionEngineTest {
    @Test
    fun completesPreludeAndSnippetsAtEmptyCursor() {
        val completion = complete("")
        assertHas(completion, "fline")
        assertHas(completion, "let")
        assertHas(completion, "hostTap")
    }

    @Test
    fun completesLocalBindingsWithInferredTypes() {
        val completion = complete(
            """
            let samples = [1, 2, 3]
            sam
            """,
        )

        val item = assertHas(completion, "samples")
        assertEquals("[Int]", item.tail)
        assertEquals("local", item.category)
    }

    @Test
    fun ranksPipeFriendlyFLineFunctionsEarly() {
        val completion = complete(
            """
            fline
              |> 
            """,
        )

        val labels = completion.metadata.map { it.displayText }
        assertTrue(labels.indexOf("moveTo") in 0..12, labels.take(20).joinToString())
        assertTrue(labels.indexOf("lineTo") in 0..12, labels.take(20).joinToString())
        assertTrue(labels.indexOf("color") in 0..12, labels.take(20).joinToString())
    }

    @Test
    fun completesRecordFieldsAfterDot() {
        val completion = complete(
            """
            let model = { label = "run", active = true, count = 4 }
            model.
            """,
        )

        assertHas(completion, "label")
        assertHas(completion, "active")
        assertHas(completion, "count")
    }

    @Test
    fun completesConstructorsFromLocalTypeDeclarations() {
        val completion = complete(
            """
            type Option a = None | Some a

            So
            """,
        )

        val item = assertHas(completion, "Some")
        assertEquals("constructor", item.tail)
    }

    @Test
    fun completesHostTapTargetsFromKotlinSource() {
        val completion = complete("""hostTap "afield.graphics.li""")

        assertTrue(
            completion.metadata.any { it.displayText == "afield.graphics.lines" },
            completion.metadata.take(20).joinToString { it.displayText },
        )
    }

    @Test
    fun completesHostTapNamedArguments() {
        val completion = complete("""hostTap "afield.graphics.lines" { ch""")

        val item = assertHas(completion, "channel")
        assertEquals("channel = ", item.text)
        assertEquals("values", item.category)
    }

    @Test
    fun describesPreludeSymbolContext() {
        val response = contextAt("fline", "fline")

        assertEquals("fline", response.symbol)
        assertTrue(response.html.contains("fline :"), response.html)
        assertTrue(response.html.contains("FieldML graphics prelude"), response.html)
    }

    @Test
    fun describesLocalBindingSymbolContext() {
        val response = contextAt(
            """
            let samples = [1, 2, 3]
            samples
            """,
            "samples",
            occurrence = 2,
        )

        assertEquals("samples", response.symbol)
        assertTrue(response.html.contains("samples : [Int]"), response.html)
        assertTrue(response.html.contains("local FieldML binding"), response.html)
    }

    @Test
    fun describesRecordFieldSymbolContext() {
        val response = contextAt(
            """
            let model = { label = "run", active = true, count = 4 }
            model.label
            """,
            "label",
            occurrence = 2,
        )

        assertEquals("label", response.symbol)
        assertTrue(response.html.contains("model.label : String"), response.html)
        assertTrue(response.html.contains("record field"), response.html)
    }

    @Test
    fun describesConstructorsFromLocalTypeDeclarations() {
        val response = contextAt(
            """
            type Option a = None | Some a

            Some 1
            """,
            "Some",
            occurrence = 2,
        )

        assertEquals("Some", response.symbol)
        assertTrue(response.html.contains("FieldML constructor"), response.html)
    }

    /**
     * The eight source-macro prelude names, at the rank a person actually **sees**.
     *
     * Completion reads `FieldMLTypePrelude.schemes` directly, so a new name is offered the moment it
     * is added — which means "is it offered" is not worth asserting on its own. What is worth
     * asserting is the two things that can silently be wrong: the *signature* shown beside the name
     * (it comes from the same `printSignature` the cheatsheet coverage guard compares against, so a
     * retyped scheme shows up in both places), and the *position*. A name at index 200 of a 250-item
     * list is not discoverable, and this project has been burned by exactly that before.
     */
    @Test
    fun theSourceMacroPreludeNamesAreOfferedWhereTheyCanBeSeen() {
        val expected = mapOf(
            "prop" to "String -> Host",
            "propOr" to "String -> 'a -> Host",
            "setProp" to "String -> 'a -> 'a",
            "rootProp" to "String -> Host",
            "setRootProp" to "String -> 'a -> 'a",
            "once" to "String -> ('a -> 'b) -> Host",
            "hostMacro" to "String -> 'a -> 'b -> Host",
        )

        // Typed far enough to be a real prefix, which is how a person meets these.
        val fromP = complete("pro").metadata.map { it.displayText }
        println("completion after 'pro': ${fromP.size} items, prop at ${fromP.indexOf("prop")}, propOr at ${fromP.indexOf("propOr")}; first 10 ${fromP.take(10)}")
        assertTrue(
            fromP.indexOf("prop") in 0..5,
            "`prop` is at ${fromP.indexOf("prop")} of ${fromP.size} after typing 'pro': ${fromP.take(8)}",
        )
        assertTrue(fromP.indexOf("propOr") in 0..5, "`propOr` is at ${fromP.indexOf("propOr")}: ${fromP.take(8)}")

        val fromMacro = complete("hostMacro").metadata.map { it.displayText }
        println("completion after 'hostMacro': ${fromMacro.size} items, first 10 ${fromMacro.take(10)}")
        assertTrue(
            fromMacro.indexOf("hostMacro") in 0..5 && fromMacro.indexOf("hostMacroExpand") in 0..5,
            "the macro names are at ${fromMacro.indexOf("hostMacro")} and " +
                "${fromMacro.indexOf("hostMacroExpand")} of ${fromMacro.size}: ${fromMacro.take(8)}",
        )

        // And every one of them, with the signature the cheatsheet also has to carry.
        val all = complete("")
        val labels = all.metadata.map { it.displayText }
        println(
            "completion at an empty caret: ${labels.size} items; " +
                expected.keys.joinToString { "$it at ${labels.indexOf(it)}" } +
                "; hostGet at ${labels.indexOf("hostGet")}, hostTap at ${labels.indexOf("hostTap")}",
        )

        // The unfiltered list is where rank decides whether a name is *seen*, and it is the honest
        // number: 130 items, `prop` at 72. Floored at 90 rather than at "present", because present
        // at index 129 is not discoverable. This is not a good position and the worklog says so; it
        // comes from the existing `preludeRank` buckets, not from anything added here.
        assertTrue(
            labels.indexOf("prop") in 0..90,
            "`prop` is at ${labels.indexOf("prop")} of ${labels.size} at an empty caret",
        )
        // For the `host…` names the useful assertion is *relative*: they must not rank worse than the
        // host names that were already there. An absolute floor would just re-encode `preludeRank`,
        // and a change to that policy should move this whole family together or fail here.
        val hostGet = labels.indexOf("hostGet")
        assertTrue(hostGet >= 0, "hostGet should be offered at an empty caret")
        listOf("hostMacro", "hostMacroExpand").forEach { name ->
            val at = labels.indexOf(name)
            assertTrue(at >= 0, "'$name' is not offered at an empty caret")
            assertTrue(
                at <= hostGet + 12,
                "'$name' is at $at, well behind hostGet at $hostGet — it has dropped out of the host family's band",
            )
        }

        // And every one of them, with the signature the cheatsheet also has to carry.
        expected.forEach { (name, signature) ->
            val item = assertHas(all, name)
            assertTrue(
                signature in item.documentation,
                "completion shows a different signature for '$name': expected '$signature' in ${item.documentation}",
            )
        }
    }

    private fun complete(source: String): Completion {
        val code = source.trimIndent()
        return completeAt(code, code.length)
    }

    /** Completion with the caret somewhere other than the end — where half-written boxes put it. */
    private fun completeAt(code: String, caret: Int): Completion =
        FieldMLCompletionEngine.complete(
            CompletionRequest(
                uid = "test",
                line = 1,
                allCode = code,
                column = 1,
                position = caret,
                property = "code",
            ),
        )

    private fun completeAfter(code: String, marker: String): Completion =
        completeAt(code, code.indexOf(marker).also { require(it >= 0) { "No '$marker' in:\n$code" } } + marker.length)

    // ---- typed host values -------------------------------------------------------------------
    //
    // These are only possible because host values carry their JVM class now. Before, a receiver's type
    // was the single opaque `Host` and there was nothing to reflect on.

    @Test
    fun completesJvmMembersInsideHostGet() {
        val completion = complete("""thisBox |> hostGet "na""")

        val item = assertHas(completion, "name")
        assertEquals("String", item.tail)
        assertTrue("Box" in item.documentation, item.documentation)
    }

    @Test
    fun completesJvmMethodsInsideHostCall() {
        val completion = complete("""fline |> hostCall "trans""")

        val item = assertHas(completion, "transform")
        assertEquals("(Function1) -> FLine", item.tail)
    }

    @Test
    fun completesJvmMembersAfterADotOnATypedHostValue() {
        val samples = (0 until 16).joinToString(", ") { "0.0" }
        val completion = complete(
            """
            let img = hostNew (4, 4, 1, [$samples]) (hostClass "afield.stdlib.Img")
            img.
            """,
        )

        assertEquals("Int", assertHas(completion, "width").tail)
        assertEquals("Int", assertHas(completion, "pixelCount").tail)
        assertEquals("Bool", assertHas(completion, "isScalar").tail)
    }

    @Test
    fun completesStaticMembersOffAClassHandle() {
        assertHas(complete("""hostClass "afield.stdlib.Img" |> hostGet "Comp"""), "Companion")
    }

    @Test
    fun completesStaticMethodsForHostExtOnTheNamedOwner() {
        val completion = complete(
            """
            let Imaging = hostClass "afield.stdlib.Imaging_fieldKt"
            fline |> hostExt Imaging "colorm
            """.trimIndent().trimEnd(),
        )
        assertHas(completion, "colormap")
    }

    @Test
    fun completesClassNamesForHostAs() {
        val completion = complete("""hostAs "Vec""")
        assertHas(completion, "Vec2")
        assertHas(completion, "Vec3")
    }

    // ---- half-written boxes ------------------------------------------------------------------
    //
    // A caret is almost never at the end of a program that parses. It is inside a record literal, an
    // argument list or a lambda, with the rest of the box still to come, and the binding it needs a
    // type for was written several lines up. These pin that case, because it is the only one that
    // happens while someone is actually typing.

    /** The demo box from `fieldml_host_bridge.md`, which is where this was first noticed. */
    private val halfWrittenRecord = """
        let Img = hostClass "afield.stdlib.Img"
        let img = hostNew (8, 4, 1, tabulate (fun i -> toFloat i / 32.0) 32) Img

        // A comment with an = sign and a "quoted bracket ( in it.
        {
          width = img.wi
          area = img.width * img.height
          first = img |> hostCall "at" (0, 0, 0)
        }
    """.trimIndent()

    @Test
    fun completesHostMembersInsideAnUnclosedRecordLiteral() {
        val item = assertHas(completeAfter(halfWrittenRecord, "img.wi"), "width")

        assertEquals("Int", item.tail)
    }

    @Test
    fun completesInsideHostGetWhileTheRestOfTheBoxIsUnparsable() {
        val code = halfWrittenRecord.replace("width = img.wi", """width = img |> hostGet "wi""")

        assertEquals("Int", assertHas(completeAfter(code, """hostGet "wi"""), "width").tail)
    }

    /** `let img = … Img` needs `let Img = …` above it, so the bindings have to be typed together. */
    @Test
    fun typesABindingThatDependsOnAnEarlierBinding() {
        val code = """
            let Img = hostClass "afield.stdlib.Img"
            let img = hostNew (8, 4, 1, tabulate (fun i -> toFloat i / 32.0) 32) Img

            img.wi
        """.trimIndent()

        assertEquals("Int", assertHas(completeAt(code, code.length), "width").tail)
    }

    @Test
    fun completesAcrossAMultiLinePipeInsideALet() {
        val code = """
            let warped =
              fline
                |> moveTo 0.0 0.0
                |> hostCall "tra
        """.trimIndent()

        assertHas(completeAt(code, code.length), "transform")
    }

    /** After a dot the only legal continuation is a member, so `with` must not be offered for `wi`. */
    @Test
    fun offersNoKeywordsOrSnippetsAfterADot() {
        val labels = completeAfter(halfWrittenRecord, "img.wi").metadata
        assertTrue(labels.none { it.category == "snippets" }, labels.map { it.displayText }.toString())
        assertTrue(labels.none { it.displayText == "with" }, labels.map { it.displayText }.toString())
    }

    // ---- inside a callback the JVM will call ---------------------------------------------------
    //
    // A lambda handed to a JVM method has typed parameters, so the popup has something to offer inside
    // the body — which is the ergonomic half of that work. Before, the only way to get here was to
    // write `hostAs "Vec3" raw` first and complete on *that*.

    @Test
    fun completesMembersOfALambdaParameterTypedFromTheJvmSignature() {
        val code = """fline |> hostCall1 "transform" (fun p -> p."""
        val completion = completeAt(code, code.length)

        assertEquals("Float", assertHas(completion, "x").tail)
        assertEquals("Float", assertHas(completion, "z").tail)
    }

    @Test
    fun completesJvmMethodsOnALambdaParameterTypedFromTheJvmSignature() {
        val code = """
            fline
              |> moveTo 0.0 0.0
              |> hostCall1 "transform" (fun p -> p |> hostCall "norm
        """.trimIndent()

        assertHas(completeAt(code, code.length), "normalize")
    }

    /** The lambda inside a tuple-literal spread: the rebuild has to carry the earlier arguments. */
    @Test
    fun completesInsideALambdaThatArrivedThroughASpread() {
        val code = """
            let Img = hostClass "afield.stdlib.Img"
            Img |> hostGet "Companion" |> hostCall "generate" (4, 4, fun x y -> toFloat (x
        """.trimIndent()

        val item = assertHas(completeAt(code, code.length), "x")
        assertEquals("Int", item.tail)
    }

    /**
     * The shipped demo document's warp box, whose page tells a reader to put the caret after `p.` and
     * ask. A multi-line pipe with an earlier lambda in it, which is the shape that actually occurs.
     */
    @Test
    fun completesInsideTheLambdaOfAMultiLinePipeline() {
        val code = """
            linspace 0.0 280.0 140
              |> map (fun x -> vec2 x 70.0)
              |> fold (fun line point -> line |> lineToV point) fline
              |> hostCall1 "transform" (fun p ->
                   vec3 p.
        """.trimIndent()

        assertEquals("Float", assertHas(completeAt(code, code.length), "y").tail)
    }

    /** A callback parameter reflection cannot type must stay out of the way, not become `Host`. */
    @Test
    fun offersNoMembersForACallbackParameterThatHasNoKnownType() {
        val code = """
            let Collections = hostClass "java.util.Collections"
            hostStaticCall "max" ([5, 1, 9, 3], fun a b -> a.
        """.trimIndent()

        assertTrue(
            completeAt(code, code.length).metadata.isEmpty(),
            completeAt(code, code.length).metadata.map { it.displayText }.toString(),
        )
    }

    /**
     * An empty answer still has to be an answer. The popup is waiting on this reply and cannot tell
     * silence from slowness, so returning nothing left it spinning until the RPC timed out.
     */
    @Test
    fun repliesWithAnEmptyCompletionWhenNothingMatches() {
        val completion = complete("zzzznosuchprefix")

        assertTrue(completion.metadata.isEmpty(), completion.metadata.map { it.displayText }.toString())
    }

    private fun contextAt(source: String, symbol: String, occurrence: Int = 1) =
        FieldMLCompletionEngine.describeSymbolContext(
            SymbolContextRequest(
                uid = "test",
                line = 1,
                allCode = source.trimIndent(),
                column = 1,
                position = occurrenceIndexOf(source.trimIndent(), symbol, occurrence) + symbol.length,
                property = "code",
            ),
        )

    private fun occurrenceIndexOf(source: String, symbol: String, occurrence: Int): Int {
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(symbol, startIndex = index + 1)
            require(index >= 0) { "Missing occurrence $occurrence of '$symbol' in:\n$source" }
        }
        return index
    }

    private fun assertHas(completion: Completion, displayText: String) =
        assertNotNull(
            completion.metadata.firstOrNull { it.displayText == displayText },
            "Missing '$displayText' in ${completion.metadata.map { it.displayText }}",
        )
}
