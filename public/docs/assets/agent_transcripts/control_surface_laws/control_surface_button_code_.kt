// A momentary button beside a checkbox, so the difference is visible rather than described.
//
// Try this: press the button, drag the pointer well off it, and let go. It still releases.
// The release is listened for on the *window* — `pointerup`, `pointercancel`, `touchend`,
// `touchcancel` and `blur` — because a gate that never closes is the defect this control
// class is famous for. Cmd-tab away mid-press and it releases then too.
//
// Also: rerun this box while holding nothing. The checkbox keeps its state (that is the
// value cache, and it is what live-coding wants). The button does not, on purpose — a
// remembered press would re-fire on every rerun, so editing one character of a DSP would
// retrigger the note.
val log = mutableListOf<String>()

controlGroup("gesture") {
    button("gate") { down ->
        log += if (down) "gate down" else "gate up"
        println(log.takeLast(6).joinToString("  "))
    }
    checkbox("bypass", initialValue = false) { on ->
        println("bypass ${if (on) "on" else "off"}")
    }
}

println("press the button: two callbacks, down then up. tick the checkbox: one, and it sticks.")
