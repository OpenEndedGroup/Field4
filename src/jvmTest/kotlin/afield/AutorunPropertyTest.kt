package afield

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `autorun` set from text — the backdoor's `set`, the browser's `set-string` — used to be stored as
 * the string "true", and reading it through `Delegation<Boolean>` threw, which ended autorun for the
 * whole document. Both halves: text is coerced on the way in, and a string already stored reads as
 * what it spells.
 */
class AutorunPropertyTest {

    private val boxes = mutableListOf<Box>()

    private fun box(id: String) = Box(id).also { boxes += it }

    @AfterTest
    fun cleanUp() {
        val mine = boxes.toSet()
        needsResync.removeAll { (b, _) -> b in mine }
    }

    @Test
    fun aStoredStringReadsAsTheBooleanItSpellsAndIsRepaired() {
        val b = box("autorun-string")
        b.setPropertyRaw("autorun", "true")
        assertEquals(true, b.autorun)
        assertEquals(true, b.getPropertyRaw("autorun"), "the string is replaced by the Boolean")

        b.setPropertyRaw("autorun", "False")
        assertEquals(false, b.autorun)
        b.setPropertyRaw("autorun", "nonsense")
        assertEquals(false, b.autorun)
    }

    @Test
    fun anUnsetAutorunIsFalseAsBefore() {
        val b = box("autorun-unset")
        assertEquals(false, b.autorun)
        assertEquals(false, b.getPropertyRaw("autorun"))
        b.autorun = true
        assertEquals(true, b.getPropertyRaw("autorun"))
    }

    @Test
    fun textIsCoercedOnlyForBooleanProperties() {
        val b = box("coerce")
        assertEquals(true, textPropertyValue(b, "autorun", "true"))
        assertEquals(false, textPropertyValue(b, "autorun", " OFF "))
        assertEquals(true, textPropertyValue(b, "isPythonRoot", "yes"))
        assertEquals("maybe", textPropertyValue(b, "autorun", "maybe"))
        // A property that is not a Boolean keeps its text, whatever it says.
        assertEquals("true", textPropertyValue(b, "code", "true"))
        assertEquals("1", textPropertyValue(b, "notes", "1"))
        // One that already holds a Boolean is one.
        b.setPropertyRaw("someFlag", false)
        assertEquals(true, textPropertyValue(b, "someFlag", "true"))
    }
}
