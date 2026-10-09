package afield.stdlib

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RuntimeIdentityTest {
    private fun temp(): File = Files.createTempDirectory("runtime-identity-").toFile()

    /** Code that does a build's work, to pin. */
    class Recipe { class Step }

    @Test fun aHostWithThePinnedCodePassesWhereverTheLibraryLives() {
        val first = File(temp(), "libsolver.dylib").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val pin = RuntimeIdentity.capture(listOf(Recipe::class), natives = listOf(first)).write(File(temp(), "runtime-identity.json"))
        val read = RuntimeIdentity.read(pin)
        assertEquals(setOf(Recipe::class.java.name), read.classes.keys)
        assertEquals(first.absolutePath, read.natives.getValue("libsolver.dylib").path)
        // The same library in another checkout or worktree is the same code: only its bytes are compared.
        val moved = File(temp(), "libsolver.dylib").apply { writeBytes(first.readBytes()) }
        RuntimeIdentity.verify(pin, natives = listOf(moved))
    }

    @Test fun otherCodeIsRefusedWithAMessageNamingEachDifference() {
        val library = File(temp(), "libsolver.dylib").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val pinned = RuntimeIdentity.capture(listOf(Recipe::class), listOf(library))
        val other = pinned.copy(classes = pinned.classes.mapValues { "0".repeat(64) } + ("afield.NotLoaded" to "1".repeat(64)))
        val rebuilt = File(temp(), "libsolver.dylib").apply { writeBytes(byteArrayOf(9)) }
        val refused = assertFailsWith<RuntimeIdentityMismatch> { RuntimeIdentity.verify(other, listOf(rebuilt)) }
        assertEquals(3, refused.differences.size, refused.message)
        assertContains(refused.message!!, "This Field loaded different code from the pinned identity")
        assertContains(refused.message!!, "${Recipe::class.java.name}: pinned 00000000, loaded ")
        assertContains(refused.message!!, "afield.NotLoaded: pinned 11111111, not loaded")
        assertContains(refused.message!!, "libsolver.dylib: pinned ")
        assertContains(refused.message!!, rebuilt.absolutePath)
        // A pinned library the caller did not hand over is not assumed to match.
        assertContains(assertFailsWith<RuntimeIdentityMismatch> { RuntimeIdentity.verify(pinned) }.message!!, "not given to verify")
    }

    @Test fun aClassPinCoversTheCodeCompiledInsideIt() {
        val pinned = RuntimeIdentity.capture(listOf(Recipe::class))
        assertEquals(classIdentity(Recipe::class), pinned.classes.getValue(Recipe::class.java.name))
        assertNotEquals(pinned.classes.values.single(), classIdentity(Recipe.Step::class))
    }
}
