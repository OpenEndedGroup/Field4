package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract of [PropertyBag]'s `keys` / `values` / `entries` views: **iterate a snapshot, write through
 * to the live map**.
 *
 * This exists because the first cut of `PropertyBag` returned bare copies, and that traded the
 * `ConcurrentModificationException` in `Box.resyncAll` for something worse — a `keys.remove(name)` or an
 * `entries.iterator().remove()` that used to work would have updated the copy and been *silently lost*.
 * Nothing in the tree does either today, which is exactly why nothing would have caught it. So the
 * write-through half is pinned here, and so is the loudness of the two operations that stay unsupported.
 */
class PropertyBagViewTest {

    private fun bagOf(vararg pairs: Pair<String, Any?>): PropertyBag =
        PropertyBag().apply { pairs.forEach { (k, v) -> put(k, v) } }

    // ---- write-through ----------------------------------------------------------------------------

    @Test
    fun keysRemoveReachesTheLiveMap() {
        val bag = bagOf("a" to 1, "b" to 2, "c" to 3)
        assertTrue(bag.keys.remove("b"))
        assertEquals(listOf("a", "c"), bag.keys.toList())
        assertFalse(bag.containsKey("b"))
        assertFalse(bag.keys.remove("b"), "removing an absent key reports no change")
    }

    @Test
    fun keysRemoveReportsTrueForANullValuedKey() {
        // `remove(k) != null` would have said "nothing removed" here, which is why it is not written
        // that way: the bag holds nulls.
        val bag = bagOf("nothing" to null)
        assertTrue(bag.keys.remove("nothing"))
        assertFalse(bag.containsKey("nothing"))
    }

    @Test
    fun keysIteratorRemoveReachesTheLiveMap() {
        val bag = bagOf("keep" to 1, "drop" to 2, "keepToo" to 3)
        val it = bag.keys.iterator()
        while (it.hasNext()) if (it.next() == "drop") it.remove()
        assertEquals(listOf("keep", "keepToo"), bag.keys.toList())
    }

    @Test
    fun keysRemoveIfReachesTheLiveMap() {
        // removeIf is java.util.Collection's default: iterator() + remove(). The one real caller of
        // this shape in the tree is agent_backdoor's tombstone sweep over box.children.
        val bag = bagOf("_a" to 1, "b" to 2, "_c" to 3)
        assertTrue(bag.keys.removeIf { it.startsWith("_") })
        assertEquals(listOf("b"), bag.keys.toList())
    }

    @Test
    fun keysRemoveAllAndRetainAllReachTheLiveMap() {
        val bag = bagOf("a" to 1, "b" to 2, "c" to 3, "d" to 4)
        assertTrue(bag.keys.removeAll(listOf("a", "d")))
        assertEquals(listOf("b", "c"), bag.keys.toList())
        assertTrue(bag.keys.retainAll(listOf("c")))
        assertEquals(listOf("c"), bag.keys.toList())
    }

    @Test
    fun entriesIteratorRemoveReachesTheLiveMap() {
        val bag = bagOf("a" to 1, "b" to 2)
        val it = bag.entries.iterator()
        while (it.hasNext()) if (it.next().key == "a") it.remove()
        assertEquals(listOf("b"), bag.keys.toList())
    }

    @Test
    fun entrySetValueReachesTheLiveMap() {
        val bag = bagOf("a" to 1, "b" to 2)
        bag.entries.forEach { if (it.key == "b") assertEquals(2, it.setValue(99)) }
        assertEquals(99, bag["b"])
        assertEquals(1, bag["a"])
    }

    @Test
    fun clearThroughKeysEmptiesTheLiveMap() {
        val bag = bagOf("a" to 1, "b" to 2)
        bag.keys.clear()
        assertTrue(bag.isEmpty())
    }

    // ---- the operations that stay unsupported are loud about it -----------------------------------

    @Test
    fun addingThroughKeysFailsLoudlyAndNamesTheAlternative() {
        val bag = bagOf("a" to 1)
        val failure = assertFailsWith<UnsupportedOperationException> { bag.keys.add("b") }
        assertTrue("add" in failure.message!!, failure.message)
        assertTrue("setPropertyRaw" in failure.message!!, failure.message)
        assertEquals(listOf("a"), bag.keys.toList(), "and it changed nothing")
    }

    @Test
    fun mutatingThroughValuesFailsLoudlyRatherThanGuessingAKey() {
        val bag = bagOf("a" to 1, "b" to 1)
        val failure = assertFailsWith<UnsupportedOperationException> { bag.values.remove(1) }
        assertTrue("ambiguous" in failure.message!!, failure.message)
        assertEquals(2, bag.size, "and it changed nothing")
    }

    // ---- reads still come from a snapshot ---------------------------------------------------------

    @Test
    fun readsComeFromASnapshotSoSelfMutationDuringIterationIsSafe() {
        val bag = bagOf("a" to 1, "b" to 2, "c" to 3)
        var seen = 0
        // Under a live view this is the textbook ConcurrentModificationException.
        bag.entries.forEach { (key, _) ->
            seen++
            bag["inserted_after_$key"] = 0
        }
        assertEquals(3, seen, "the walk saw the snapshot, not the insertions")
        assertEquals(6, bag.size)
    }

    @Test
    fun viewsKeepInsertionOrderAndSetEquality() {
        val bag = bagOf("zebra" to 1, "alpha" to 2, "mike" to 3)
        assertEquals(listOf("zebra", "alpha", "mike"), bag.keys.toList())
        assertEquals(listOf<Any?>(1, 2, 3), bag.values.toList())
        assertEquals(listOf("zebra", "alpha", "mike"), bag.entries.map { it.key })
        assertEquals(setOf("zebra", "alpha", "mike"), bag.keys, "keys must still equal an ordinary Set")
    }

    @Test
    fun theBagItselfStillBehavesLikeALinkedHashMap() {
        val bag = bagOf("a" to 1, "b" to null)
        assertEquals(mapOf<String, Any?>("a" to 1, "b" to null), bag)
        assertEquals(2, bag.size)
        assertNull(bag["b"])
        assertTrue(bag.containsKey("b"))
        assertEquals(1, bag.remove("a"))
        assertEquals(1, bag.size)
    }
}
