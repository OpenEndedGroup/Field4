package afield.math.linalg.codegen

import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class GenerationPackagesTest {
    private val alternate = """
        linalg.package=preview.math.linalg
        extensions.package=preview.math.extensions
        halfCodec.package=preview.math.half
        completionAnnotation.type=preview.ui.CompletionCategory
    """.trimIndent()

    @Test fun configurationMovesPathsDeclarationsImportsAnnotationsAndDocsTogether() {
        val packages = GenerationPackages.read(alternate.byteInputStream())
        val emitter = KotlinEmitter(FieldAlgebraModel.build(), packages = packages)
        val files = generatedSources(packages, emitter)
        assertEquals(9, files.size)
        files.forEach { (path, text) ->
            val declaration = text.lineSequence().single { it.startsWith("package ") }.removePrefix("package ")
            assertEquals(java.nio.file.Path.of(declaration.replace('.', '/')), path.parent)
            assertFalse("afield." in text, path.toString())
        }
        assertContains(files.getValue(packages.sourcePath(packages.extensions, "Swizzles.kt")), "@file:preview.ui.CompletionCategory")
        assertContains(files.getValue(packages.sourcePath(packages.linalg, "Vectors.kt")), "import preview.math.half.floatToHalfBits")
        assertContains(emitter.emitReferenceMarkdown(), "`preview.math.linalg.bulk`")
    }

    @Test fun packageMoveChecksAndRemovesOnlyObsoleteOwnedOutputs() {
        val root = Files.createTempDirectory("linalg-package-test")
        try {
            val output = root.resolve("sources")
            val docs = root.resolve("docs")
            val config = root.resolve("packages.properties").also { it.writeText(alternate) }
            val base = arrayOf("--output", output.toString(), "--docs-output", docs.toString())
            main(base)
            val oldVector = output.resolve(GenerationPackages.default().sourcePath(GenerationPackages.default().linalg, "Vectors.kt"))
            val oldContent = oldVector.readText()
            val handwritten = output.resolve("handwritten/Vectors.kt").also { it.parent.createDirectories(); it.writeText("// keep user source\n") }
            val migrated = base + arrayOf("--packages", config.toString())
            main(migrated)
            assertFalse(oldVector.exists())
            assertEquals("// keep user source\n", handwritten.readText())
            main(migrated + "--check")
            oldVector.writeText(oldContent)
            val failure = assertFailsWith<IllegalStateException> { main(migrated + "--check") }
            assertContains(failure.message.orEmpty(), "obsolete generated source")
            assertTrue(oldVector.exists(), "check mode must not delete")
            main(migrated)
            assertFalse(oldVector.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun invalidUnknownMissingAndDuplicateSettingsFailBeforeEmission() {
        for (text in listOf(
            alternate.replace("preview.math.linalg", "../outside"),
            alternate.replace("preview.math.linalg", "preview.class.linalg"),
            alternate + "\nextra.package=preview.extra",
            alternate.substringAfter('\n'),
            alternate + "\nlinalg.package=preview.other",
        )) assertFailsWith<IllegalArgumentException> { GenerationPackages.read(text.byteInputStream()) }
    }
}
