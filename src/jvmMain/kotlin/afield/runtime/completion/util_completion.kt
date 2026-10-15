package afield.runtime.completion

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.long
import afield.ui.notation.with

import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.app.settings.PersistOnExit.needingPersistance
import afield.document.persistence.SavingContext.Companion.format
import afield.app.diagnostics.logging.lprintln
import afield.data.collections.invoke
import afield.math.linalg.invoke
import java.io.File
import java.lang.reflect.*
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class CompletionCategory(val value: String)

/**
 * The handful of members that answer "what do you actually do with one of these", in the order a person
 * reaches for them — a **ranking prior** for the completion popup, declared where the type is.
 *
 * Why an annotation rather than a heuristic: at a bare `f.` there is no prefix to fuzzy-match, so the
 * popup ranks on category weight plus the order the server sent, and the only term that could float
 * `moveTo` above `breakNext` is `recency` — which lives in `localStorage`, hence empty for a new user, in
 * a fresh browser profile, and in every test. A list in the source is the same answer for all four.
 *
 * Names, not callable references, so one entry covers every overload of a name (`FLine.moveTo` has four)
 * and a property reads the same as a function. A name that does not resolve would be a silent no-op, so
 * `CommonMembersAnnotationTest` fails the build if any listed name is not a member of the annotated
 * class — a typo would otherwise just quietly stop ranking.
 *
 * Keep these short. This is the first page of the manual, not an index: past roughly ten the order stops
 * being a real claim about what people reach for, and [afield.runtime.completion.CommonMemberPrior] scores by position, so a
 * long tail earns almost nothing anyway.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class CommonMembers(vararg val value: String)

