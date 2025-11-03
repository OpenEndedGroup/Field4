package afield

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
