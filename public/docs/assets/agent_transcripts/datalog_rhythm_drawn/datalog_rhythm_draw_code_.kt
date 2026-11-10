// (step, lane): lane 0 is kick, 1 snare, 2 hh. Edit and run again to redraw.
val notes = [0 to 0, 6 to 0, 10 to 0, 4 to 1, 12 to 1, 2 to 2, 14 to 2]
notation {
    rect(400, 60, 480, 90, name = "drums")
    for ((step, lane) in notes) rect(400 + step * 30 + 4, 60 + lane * 30 + 8, 14, 14, name = "n$step.$lane")
}
println("drew ${notes.size} marks in 'drums'")
