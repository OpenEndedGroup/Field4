@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box

/**
 * A block of code that is also its own source: call it like a function, and it carries the Kotlin
 * text it was written as.
 *
 * ```
 * val answer = sourceBlock { 6 * 7 }
 * println(answer())          // 42
 * println(answer.source)     // 6 * 7
 * ```
 *
 * A macro: before the box compiles, `sourceBlock { … }` is rewritten to a [SourceBackedBlock] holding
 * both the live lambda and the text of its body. Code that saves a block — a keyframe track, a
 * code-blend plan — saves that text, and a block loaded back from it compiles the text again, in the
 * box it came from, the first time it is called. So a saved block works before (or without) the box
 * that wrote it running again.
 *
 * A block made by this call carries its lambda and is safe to call at once. A block loaded back from
 * saved text is not safe to call from inside its own box's running code: compiling it runs that box's
 * code again, which calls the block again. Call it from another box, a frame loop or a control
 * callback; [SourceBackedBlockSelfBoxReentry] stops the loop and says so if you do.
 *
 * Should the macro not run (code that reaches this function some other way), the result still works,
 * with an empty [SourceBackedBlock.source].
 */
fun <T> sourceBlock(block: () -> T): SourceBackedBlock<T> =
    SourceBackedBlock(Box.context.get()?.id ?: "", "", block)
