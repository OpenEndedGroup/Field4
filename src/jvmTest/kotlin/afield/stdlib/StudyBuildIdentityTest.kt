package afield.stdlib

import afield.stdlib.StudyBuildTestSupport.temp
import java.io.File
import kotlin.test.*

class StudyBuildIdentityTest {
    @Test fun fileIdentityFollowsContentsAndNamesButNotWhereTheFilesLive() {
        val here = File(temp(), "provider").apply { mkdirs() }
        File(here, "route.py").writeText("def route(): pass\n")
        File(here, "lib/curves.py").apply { parentFile.mkdirs() }.writeText("K = 1\n")
        val identity = fileIdentity(File(here, "route.py"), File(here, "lib"))
        assertEquals(64, identity.length)

        val moved = File(temp(), "provider").also { here.copyRecursively(it) }
        assertEquals(identity, fileIdentity(File(moved, "route.py"), File(moved, "lib")))

        // Hidden files and Python bytecode are not code the build runs.
        File(here, "lib/.DS_Store").writeText("finder")
        File(here, "lib/__pycache__/curves.cpython-312.pyc").apply { parentFile.mkdirs() }.writeText("bytecode")
        assertEquals(identity, fileIdentity(File(here, "route.py"), File(here, "lib")))

        File(here, "lib/curves.py").writeText("K = 2\n")
        assertNotEquals(identity, fileIdentity(File(here, "route.py"), File(here, "lib")))
        File(here, "lib/curves.py").writeText("K = 1\n")
        File(here, "route.py").renameTo(File(here, "routes.py"))
        assertNotEquals(identity, fileIdentity(File(here, "routes.py"), File(here, "lib")))
    }

    @Test fun fileIdentityRefusesMissingAndAmbiguousFiles() {
        val a = File(temp(), "same.txt").apply { writeText("a") }
        val b = File(temp(), "same.txt").apply { writeText("b") }
        assertFailsWith<IllegalArgumentException> { fileIdentity(a, b) }
        assertFailsWith<IllegalArgumentException> { fileIdentity(File(temp(), "absent.txt")) }
        assertFailsWith<IllegalArgumentException> { fileIdentity(emptyList()) }
    }

    /** A class with compiled code inside it, to check that classIdentity reads all of it. */
    class Recipe {
        class Step
        companion object { const val VERSION = 1 }
        fun anonymous(): Any = object : Runnable { override fun run() = Unit }
    }

    @Test fun classIdentityCoversTheClassAndTheCodeCompiledInsideIt() {
        val compiled = compiledClasses(Recipe::class.java).keys
        val outer = Recipe::class.java.name
        assertTrue("$outer\$Step" in compiled, "nested class missing: $compiled")
        assertTrue("$outer\$Companion" in compiled, "companion missing: $compiled")
        assertTrue(compiled.any { it.startsWith("$outer\$anonymous\$") }, "anonymous object missing: $compiled")
        assertFalse(compiled.any { it == StudyBuildIdentityTest::class.java.name }, "the enclosing class is not part of it")

        assertEquals(classIdentity(Recipe::class), classIdentity(Recipe::class))
        assertNotEquals(classIdentity(Recipe::class), classIdentity(Recipe.Step::class))
        assertEquals(classIdentity(Recipe::class, StudyBuilds::class), classIdentity(listOf(StudyBuilds::class, Recipe::class)))
        // Classes from a jar are read too.
        assertEquals(64, classIdentity(kotlin.time.Duration::class).length)
    }
}
