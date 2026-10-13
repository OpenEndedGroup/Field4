package afield.tools.refactor

import kotlin.test.*

class EngineTest {
    private val recipe = Recipe(mapOf("afield.old" to "afield.newplace"),
        mapOf("afield.mixed.Thing" to "afield.model.Thing"), setOf("afield.mixed"))

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
}
