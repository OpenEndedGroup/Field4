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

import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
fun findDefinition(a: Any?, search: Collection<*>?): String {
    if (a == null) return "null"
    if (a is Number) return a.toString()
    if (a is Boolean) return a.toString()
    val d = search
        ?.mapNotNull { it as? Box }
        ?.firstNotNullOfOrNull { box ->
        box.properties.entries.firstNotNullOfOrNull {
            if (it.value === a) "'${it.key}' of box '${box.name}'"
            else null
        } ?: box.ki.context.vars.entries.firstNotNullOfOrNull {
            if (it.value.value === a) "variable '${it.key}' of box '${box.name}'"
            else null
        }
        }

    if (d != null) return "$a ($d)"
    else return "$a"
}
