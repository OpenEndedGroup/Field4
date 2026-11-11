@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.study.source

import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.properties.saved
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.lifecycle.once

import afield.audio.synth.*
import afield.data.collections.get
import afield.document.graph.Box

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
 * both the live lambda and the text of its body. Code that saves a block (a keyframe track, a
 * code-blend plan) saves that text, and a block loaded back from it compiles the text again, in the
 * box it came from, the first time it is called. So a saved block works before (or without) the box
 * that wrote it running again.
 *
 * A typed one-argument lambda uses the same flat spelling:
 * `sourceBlock { screenNo: Int -> 0.1 + 0.04 * screenNo }`. It produces a
 * [SourceBackedFunction] retaining the complete lambda, including its parameter type. This form
 * is a Field box macro; compiled Kotlin can construct [SourceBackedFunction] explicitly.
 *
 * Restored blocks compile once. During their owner's body they use a separate engine with the
 * inherited preamble, avoiding recursive body execution. Inherited properties remain available;
 * unexported local variables are not durable captures and must be written into the source instead.
 *
 * Should the macro not run (code that reaches this function some other way), the result still works,
 * with an empty [SourceBackedBlock.source].
 */

/**
 * A block carrying [block] and no source, which is what `sourceBlock { … }` compiles to when the
 * macro has not rewritten it.
 */
fun <T> sourceBlock(block: () -> T): SourceBackedBlock<T> =
    SourceBackedBlock(Box.context.get()?.id ?: "", "", block)
