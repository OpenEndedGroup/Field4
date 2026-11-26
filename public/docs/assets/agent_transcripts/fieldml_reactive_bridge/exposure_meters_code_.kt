prop<Double>(mappedOpacity)

val measured = lightLevel
val mapped = mappedOpacity
val track = 320.0
val bars =
    [measured to colorHex("#c4d5e7"), mapped to colorHex("#8ed2b3")].flatMapIndexed { row, (value, ink) ->
        val background = fline {
            rect(0.0, row * 30.0, track, 12.0)
            filled = true
            stroked = false
            color = colorHex("#29303c")
        }
        val foreground = fline {
            rect(0.0, row * 30.0, track * value, 12.0)
            filled = true
            stroked = false
            color = ink
        }
        [background, foreground]
    }
lines(bars, channel = "values", w = track, h = 50.0)
markdown("readout", "Light level **${measured.f3}** · mapped opacity **${mapped.f3}**")
displayOpen("values")
println("Light ${measured.f3} · opacity ${mapped.f3}")
