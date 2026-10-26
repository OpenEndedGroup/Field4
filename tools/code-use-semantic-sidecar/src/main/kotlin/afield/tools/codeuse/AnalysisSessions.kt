package afield.tools.codeuse

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.psi.KtElement

/** Keep session extension receivers in the existing analysis code while using the context API. */
internal inline fun <R> withAnalysis(element: KtElement, action: KaSession.() -> R): R =
    analyze(element) { with(contextOf<KaSession>()) { action() } }
