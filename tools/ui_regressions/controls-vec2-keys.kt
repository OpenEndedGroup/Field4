// A vec2 pad that is focusable, nudged by arrow keys, and reset by double-click or Backspace.
var pos = Vector2f(0.2f, -0.3f)
controlGroup("controls") {
    vec2Control("pos", Vector2f(0.2f, -0.3f), step = 0.05) { pos = it }
}
displayOpen("controls")
