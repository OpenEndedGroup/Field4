package afield


fun findDefinition(a: Any?, search: Collection<Box>?): String {
    if (a == null) return "null"
    val d = search?.firstNotNullOfOrNull { box ->
        box.properties.entries.firstNotNullOfOrNull {
            if (it.value === a) "'${it.key}' of box '${box.name}"
            else null
        } ?: box.ki.context.vars.entries.firstNotNullOfOrNull {
            if (it.value.value === a) "variable '${it.key}' of box ${box.name}"
            else null
        }
    }

    if (d != null) return "$a ($d)"
    else return "$a"
}
