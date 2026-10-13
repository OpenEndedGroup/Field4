package afield.tools.refactor

import kotlin.test.*
import kotlinx.serialization.json.*

class EngineTest {
    private val recipe = Recipe(mapOf("afield.old" to "afield.newplace"),
        mapOf("afield.mixed.Thing" to "afield.model.Thing"), setOf("afield.mixed"))

    @Test fun declarationInventoryKeepsOverloadsButNotMembersOrLocals() = Engine().use { engine ->
        val result = engine.declarations("package sample\r\nclass Thing { fun member() {} }\r\n" +
            "fun String.convert(x: Int) { fun local() {} }\r\nfun convert(x: Double) = x\r\nprivate val hidden = 2")
        val rows = result.getValue("declarations").jsonArray
        assertEquals(listOf("sample.Thing", "sample.convert", "sample.convert", "sample.hidden"),
            rows.map { it.jsonObject.getValue("fqn").jsonPrimitive.content })
        assertEquals("private", rows.last().jsonObject.getValue("visibility").jsonPrimitive.content)
        assertTrue(rows[1].jsonObject.getValue("signature").jsonPrimitive.content.contains("String.convert"))
        assertTrue(engine.declarations("package sample\nfun broken(").getValue("declarations").jsonArray.isEmpty())
    }

    @Test fun splitPackageOwnershipExcludesKnownChildrenAndRetainedFamilies() = Engine().use { engine ->
        val r = Recipe(emptyMap(), mapOf("afield.Color4" to "afield.graphics.color.Color4"), setOf("afield"),
            knownPackages = setOf("afield", "afield.math.gp", "afield.graphics.color"), retainedSymbols = setOf("afield.Stays"))
        val source = "package afield.math.gp\nimport afield.Color4\nimport afield.Stays\n" +
            "// afield.math.gp.GaussianProcess\nval x: afield.math.gp.GaussianProcess? = null\n"
        val after = engine.rewrite(source, false, r)
        assertEquals(source.replace("import afield.Color4", "import afield.graphics.color.Color4"), after.code)
        assertTrue(after.findings.isEmpty())
        assertTrue(engine.rewrite("import afield.Unknown", true, r).findings.any { it.kind == "unmapped-import" })
        assertTrue(engine.rewrite("import afield.*", true, r).findings.any { it.kind == "split-star" })
    }

    @Test fun explicitCommentPassRewritesNamesWithoutTouchingStrings() = Engine().use { engine ->
        val source = "/** See [afield.old.Thing], afield.old. and afield.mixed.Thing.Nested. */\r\n" +
            "// afield.older.Thing and host.afield.old.Thing stay.\r\nval name = \"afield.old.Thing\"\r\n"
        val result = engine.rewrite(source, true, recipe.copy(rewriteComments = true))
        assertEquals("/** See [afield.newplace.Thing], afield.newplace. and afield.model.Thing.Nested. */\r\n" +
            "// afield.older.Thing and host.afield.old.Thing stay.\r\nval name = \"afield.old.Thing\"\r\n", result.code)
        assertEquals(listOf("string-reference"), result.findings.map { it.kind })
        assertEquals(result.code, engine.rewrite(result.code, true, recipe.copy(rewriteComments = true)).code)
    }

    @Test fun unmappedAndPathLikeCommentsStayReviewFindings() = Engine().use { engine ->
        val source = "// afield.mixed.Other and afield.old.Thing/file\nval x = 1"
        val result = engine.rewrite(source, true, recipe.copy(rewriteComments = true))
        assertEquals(source, result.code)
        assertTrue(result.findings.any { it.kind == "comment-reference" })
    }

    @Test fun importsPreserveAliasesCommentsAndCrlf() = Engine().use { engine ->
        val source = "// import afield.old.Fake\r\nimport afield.old.Thing as T // keep\r\nimport afield.old.*\r\nval x = T()\r\n"
        val result = engine.rewrite(source, true, recipe)
        assertEquals(listOf("comment-reference"), result.findings.map { it.kind })
        assertEquals(source.replace("import afield.old.Thing", "import afield.newplace.Thing")
            .replace("import afield.old.*", "import afield.newplace.*"), result.code)
        assertEquals(result.code, engine.rewrite(result.code, true, recipe).code)
    }

    @Test fun splitStarAndUnmappedImportNeedReview() = Engine().use { engine ->
        val result = engine.rewrite("import afield.mixed.*\nimport afield.mixed.Other\nval x = 1", true, recipe)
        assertEquals(setOf("split-star", "unmapped-import"), result.findings.map { it.kind }.toSet())
    }

    @Test fun individualMovesMapNestedImports() = Engine().use { engine ->
        val result = engine.rewrite("import afield.mixed.Thing.Nested as N\nval x = 1", true, recipe)
        assertEquals("import afield.model.Thing.Nested as N\nval x = 1", result.code)
        val body = engine.rewrite("val x: afield.mixed.Thing.Nested = afield.mixed.Thing.Nested()", true, recipe)
        assertEquals("val x: afield.model.Thing.Nested = afield.model.Thing.Nested()", body.code)
        assertEquals(emptyList(), body.findings)
    }

    @Test fun localQualifiersAndStringsAreNeverGuessed() = Engine().use { engine ->
        val source = "val afield = factory()\nval x = afield.old.Thing()\nval s = \"afield.old.Thing\""
        val result = engine.rewrite(source, true, recipe)
        assertEquals(source, result.code)
        assertTrue(result.findings.any { it.kind == "qualified-reference" })
        assertTrue(result.findings.any { it.kind == "string-reference" })
    }

    @Test fun syntaxFailureDoesNotEditAndSourceMovesAreHeld() = Engine().use { engine ->
        val broken = "import afield.old.Thing\nval x = ("
        assertEquals(broken, engine.rewrite(broken, true, recipe).code)
        assertTrue(engine.rewrite(broken, true, recipe).findings.any { it.kind == "parse-error" })
        assertTrue(engine.rewrite("package afield.mixed\nclass Thing", false, recipe).findings.any { it.kind == "source-package" })
        assertEquals("package afield.newplace\nclass Thing", engine.rewrite("package afield.old\nclass Thing", false, recipe).code)
    }

    @Test fun prefixesMatchComponentsAndUtf16EditsStayInsideKotlin() = Engine().use { engine ->
        val source = "// 🎹\nimport afield.older.Thing\nimport afield.old.Other\nval x = 1"
        assertEquals(source.replace("afield.old.Other", "afield.newplace.Other"), engine.rewrite(source, true, recipe).code)
    }

    @Test fun qualifiedTypesCallsAndNestedArgumentsAreRewritten() = Engine().use { engine ->
        val source = "// 🎹\r\nval x: afield.old.Thing<afield.old.Other>? = afield.old.Thing(afield.old.Other()).copy()\r\n"
        val result = engine.rewrite(source, true, recipe)
        assertEquals(source.replace("afield.old.", "afield.newplace."), result.code)
        assertEquals(emptyList(), result.findings)
        assertEquals(result.code, engine.rewrite(result.code, true, recipe).code)
    }

    @Test fun symbolRuleWinsOverParentPackageExpression() = Engine().use { engine ->
        val rules = recipe.copy(symbols = mapOf("afield.old.Thing" to "afield.model.NewThing"))
        val source = "val x: afield.old.Thing.Nested = afield.old.Thing.Nested()"
        assertEquals(source.replace("afield.old.Thing", "afield.model.NewThing"), engine.rewrite(source, true, rules).code)
    }

    @Test fun callResultsAliasesAndPackageLikeLocalNamesArePreserved() = Engine().use { engine ->
        val source = "import other.Value as afield\nval x = afield.old.Thing()\nval y = factory().afield.old.Thing()"
        assertEquals(source, engine.rewrite(source, true, recipe).code)
        val local = "fun f(afield: Value) = afield.old.Thing()"
        assertEquals(local, engine.rewrite(local, true, recipe).code)
        val factory = "val x = factory().afield.old.Thing()"
        assertEquals(factory, engine.rewrite(factory, true, recipe).code)
        val shadow = "val other = factory()\nval x = afield.old.Thing()"
        val rules = recipe.copy(packages = mapOf("afield.old" to "other.newplace"))
        assertEquals(shadow, engine.rewrite(shadow, true, rules).code)
    }

    @Test fun prefixTriviaAndGenericOwnersAreHeldWithoutLosingSyntax() = Engine().use { engine ->
        val commented = "val x: afield./* keep */old.Thing = TODO()"
        assertEquals(commented, engine.rewrite(commented, true, recipe).code)
        val rules = recipe.copy(symbols = mapOf("afield.old.Owner.Nested" to "afield.newplace.NewNested"))
        val generic = "val x: afield.old.Owner<String>.Nested = TODO()"
        val result = engine.rewrite(generic, true, rules)
        assertEquals(generic, result.code)
        assertTrue(result.findings.any { it.kind == "qualified-type" })
    }

    @Test fun identicalIntroImportsAreDroppedOnlyInScripts() = Engine().use { engine ->
        val rules = recipe.copy(scriptImports = setOf("afield.newplace.*", "afield.newplace.Thing"))
        val source = "import afield.old.*\r\nimport afield.old.Thing // keep\r\nimport afield.old.Other\r\nimport afield.old.Thing as T\r\nval x = T()\r\n"
        val result = engine.rewrite(source, true, rules)
        assertEquals("\r\n // keep\r\nimport afield.newplace.Other\r\nimport afield.newplace.Thing as T\r\nval x = T()\r\n", result.code)
        assertEquals(source.replace("afield.old", "afield.newplace"), engine.rewrite(source, false, rules).code)
        assertEquals(result.code, engine.rewrite(result.code, true, rules).code)
    }
}
