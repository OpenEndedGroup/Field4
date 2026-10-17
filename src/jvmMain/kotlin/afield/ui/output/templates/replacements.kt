package afield.ui.output.templates

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
// a micro templating system

val templates = mutableMapOf<String, (String, String) -> String>()
val knownValues = mutableMapOf<String, String>()

fun replaceTemplate(input: String, replacer: (placeholder: String, wholeText: String) -> String): String {
    // The regex captures anything between "{{" and "}}", allowing for optional whitespace.
    val regex = Regex("""\{\{\s*(.*?)\s*\}\}""")
    return regex.replace(input) { matchResult ->
        val placeholder = matchResult.groupValues[1]
        replacer(placeholder, input)
    }
}

fun filter(source: String) = replaceTemplate(source) { template, everything ->
    knownValues[template] ?: templates[template]?.invoke(template, everything) ?: ""
}
