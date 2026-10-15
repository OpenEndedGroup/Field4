@file:OptIn(ExperimentalJsExport::class, ExperimentalUuidApi::class)

package afield.app.notifications

import afield.app.canvas.invoke

import CanvasGestures
import clipRay
import connectCenters
import getCenter
import doubleTap
import handleCustomizationRequest
import handleEvalRequest
import handleOutputAreaRequest
import handleOutputAreaControl
import clearOutputAreaContents
import teardownBoxOutputs
import kotlinext.js.asJsObject
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.css.*
import kotlinx.dom.hasClass
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import org.w3c.dom.*
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGSVGElement
import org.w3c.dom.url.URLSearchParams
import registerDoubleTap
import tripleTap
import web.cssom.important
import web.timers.clearTimeout
import web.timers.setTimeout
import kotlin.js.Date
import kotlin.js.json
import kotlin.js.jsTypeOf
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

external fun notify(text: String, category: String)

