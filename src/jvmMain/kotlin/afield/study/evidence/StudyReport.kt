@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.study.evidence

/**
 * The common result type of Field's study tools: a revision, a map of measured facts, and the images
 * that accompany them.
 *
 * [StudyReport] is what the function, StackSL, RectSL, stack.auto and source-frames studies return to a model.
 */

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
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
import afield.runtime.execution.go

import afield.audio.synth.*
import afield.ai.tools.CarriesToolImages
import afield.ai.tools.SummarisesForToolLog
import afield.ai.tools.ToolImage

/**
 * What every study tool answers with: the revision it is about, the facts it measured, and the
 * pictures that go with them.
 *
 * [facts] is serialised as the tool's JSON output; [toolImages] travel as images, never as text (see
 * [CarriesToolImages]). The same class is returned by the function, StackSL, RectSL and stack.auto
 * studies, so a result reads the same whichever study produced it.
 */
data class StudyReport(val revision: String, val facts: Map<String, Any?>,
    override val toolImages: List<ToolImage> = emptyList()) : CarriesToolImages, SummarisesForToolLog {
    override fun toolLogSummary() = "$revision ${facts["message"] ?: facts.keys.joinToString()} images=${toolImages.size}"
}
