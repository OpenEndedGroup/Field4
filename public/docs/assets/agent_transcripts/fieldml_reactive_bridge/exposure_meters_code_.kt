prop<Double>(mappedOpacity)

val measured = lightLevel
val mapped = mappedOpacity
val track = 320

fun row(label: String, value: Double, color: String): String {
    val width = (track * value).toInt()
    return """
        <p>$label ${value.f3}</p>
        <div style="width:${track}px;background:#29303c">
            <div style="width:${width}px;height:12px;background:$color"></div>
        </div>""".trimIndent()
}

display("values", row("Light level", measured, "#c4d5e7") + row("Mapped opacity", mapped, "#8ed2b3"))
displayOpen("values")
println("Light ${measured.f3} · opacity ${mapped.f3}")
