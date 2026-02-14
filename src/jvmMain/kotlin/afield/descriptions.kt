package afield


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
