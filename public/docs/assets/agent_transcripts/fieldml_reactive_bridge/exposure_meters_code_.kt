prop<Double>(mappedOpacity)

val measured = lightLevel
val mapped = mappedOpacity
val track = 320.0
fun bar(row: Int, fraction: Double, ink: Vec4) = fline {
    rect(0.0, row * 30.0, track * fraction, 12.0)
    filled = true
    stroked = false
    color = ink
}
val background = colorHex("#29303c")
val bars = [
    bar(0, 1.0, background),
    bar(0, measured, colorHex("#c4d5e7")),
    bar(1, 1.0, background),
    bar(1, mapped, colorHex("#8ed2b3")),
]
display("values", drawToSVG(bars, w = track, h = 50.0))
markdown("readout", "Light level **${measured.f3}** · mapped opacity **${mapped.f3}**")
displayOpen("values")
println("Light ${measured.f3} · opacity ${mapped.f3}")
