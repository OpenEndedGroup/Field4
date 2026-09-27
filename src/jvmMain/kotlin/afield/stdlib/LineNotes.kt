@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a box's program shows at one of its lines — a sentence, and a picture when there is one —
 * offered wherever the editor looks at a line: at the top of the completion popup opened on it, and in
 * the notes beside the cursor.
 *
 * A language that can evaluate part of a program says so by registering a [Provider] for the property
 * its programs live in. StackSL draws the stage the line ends, the picture its study card shows for
 * that line; RectSL the scene built up to the line, with the rectangles the line makes outlined. Nothing
 * here knows either language: the editor asks with a `line-note` request, and Field hands the box, its
 * current text and the line to the first provider that takes that box.
 */
object LineNotes {
    /** A line's note: [caption] a sentence about it, [png] a small picture of what it evaluates to, or null. */
    class Note(val caption: String, val png: ByteArray? = null)

    /** Describes [line] (1-based) of [source], the text of [box]'s property as the editor holds it; null when it has nothing to say. */
    fun interface Provider {
        fun note(box: Box, source: String, line: Int): Note?
    }

    private class Registration(val applies: (Box) -> Boolean, val provider: Provider)

    private val providers = ConcurrentHashMap<String, CopyOnWriteArrayList<Registration>>()

    /**
     * Offer notes for [property] of the boxes [applies] accepts. The newest registration that accepts a
     * box answers for it. Providers are called off the main thread, one at a time, and may take a
     * moment — a picture is usually rendered — so they should cache.
     */
    fun register(property: String, applies: (Box) -> Boolean = { true }, provider: Provider) {
        providers.getOrPut(property) { CopyOnWriteArrayList() }.add(0, Registration(applies, provider))
    }

    /** The note for [line] of [box]'s [property], holding [source]; null when no provider takes the box or it has nothing to say. */
    fun note(box: Box, property: String, source: String, line: Int): Note? =
        providers[property]?.firstOrNull { runCatching { it.applies(box) }.getOrDefault(false) }?.provider?.note(box, source, line)
}
