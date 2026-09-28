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
 * current text and the line to the providers that take that box, newest first, until one has something
 * to say — so a language's own provider and the one that finds StackSL embedded in Kotlin both get
 * their turn on a `code` property.
 *
 * Languages that cannot evaluate part of a program without running it — Forth, Clojure, Python — say
 * instead what each line produced *the last time the box ran*, recorded while it ran: see
 * [LastRunNotes]. Datalog answers from the model the box's runs have built. None of them runs any of
 * your code to make a note.
 */
object LineNotes {
    /**
     * A line's note: [caption] a sentence about it, [png] a small picture of what it evaluates to, or null.
     * [volatile] says the note can change while the text does not — a last run's value, which the next
     * run replaces, or its age — so the editor asks again rather than keeping it against the text.
     */
    class Note(
        val caption: String,
        val png: ByteArray? = null,
        val volatile: Boolean = false,
        /**
         * The note split for the eye: [value], what the line came to, with its results marked [Segment.strong]
         * so the editor sets them in bold, and [meta], where the value came from ("last run, 12 s ago"). Empty
         * for a note that is only a [caption] (StackSL's, RectSL's), which the editor shows as before.
         * [caption] stays the whole sentence, for anything that reads the note as text.
         */
        val meta: String = "",
        val value: List<Segment> = emptyList(),
        /** Device pixels per CSS pixel of [png]: 2 for a picture drawn for a Retina display. */
        val imageScale: Int = 1,
    )

    /** A run of a note's [Note.value]: [strong] for a result — a number, a stack value, an answer count. */
    class Segment(val text: String, val strong: Boolean = false) {
        override fun toString() = text
    }

    /** A plain run of a note's value. */
    fun plain(text: String) = Segment(text, false)

    /** A result in a note's value, which the editor sets in bold. */
    fun strong(text: String) = Segment(text, true)

    /** Describes [line] (1-based) of [source], the text of [box]'s property as the editor holds it; null when it has nothing to say. */
    fun interface Provider {
        fun note(box: Box, source: String, line: Int): Note?
    }

    private class Registration(val applies: (Box) -> Boolean, val provider: Provider)

    private val providers = ConcurrentHashMap<String, CopyOnWriteArrayList<Registration>>()

    /**
     * Offer notes for [property] of the boxes [applies] accepts. Registrations that accept a box are
     * asked newest first, and the first note wins; a provider with nothing to say returns null and
     * the next is asked. Providers are called off the main thread, one at a time, and may take a
     * moment — a picture is usually rendered — so they should cache.
     */
    fun register(property: String, applies: (Box) -> Boolean = { true }, provider: Provider) {
        providers.getOrPut(property) { CopyOnWriteArrayList() }.add(0, Registration(applies, provider))
    }

    /** Whether any provider takes [box]'s [property] — whether asking for its notes can say anything at all. */
    fun offersFor(box: Box, property: String): Boolean =
        providers[property]?.any { runCatching { it.applies(box) }.getOrDefault(false) } == true

    /** The note for [line] of [box]'s [property], holding [source]; null when no provider takes the box or none has anything to say. */
    fun note(box: Box, property: String, source: String, line: Int): Note? =
        providers[property]?.asSequence()
            ?.filter { runCatching { it.applies(box) }.getOrDefault(false) }
            ?.firstNotNullOfOrNull { it.provider.note(box, source, line) }
}
