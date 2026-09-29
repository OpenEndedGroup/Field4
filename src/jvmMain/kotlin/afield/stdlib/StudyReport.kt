@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.ai.core.CarriesToolImages
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.ToolImage

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
