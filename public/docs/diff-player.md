
# Code Diff Player for MkDocs

The diff player assets are included site-wide via `mkdocs.yml`, so you only need the markup. The player is styled to match the Material dark theme gutter and line numbers.

## Quick start
- Use the `diff` fence for animated before/after, and `diffstatic` for a single snippet.
- The fence-upgrade script rewrites these into the player markup automatically, then `CodeDiffPlayer.mountAll()` binds them.

Example animated fence:

```diff {data-lang=kotlin}
:::before
lines.clear()

loop {
  var f = FLine()

  while (stereo.controllerState().triggerValue > 0.0) {
    val at = stereo.controllerState().position
    f.lineTo(at)
    lines["ourLine"] = f
    frame()
  }

  frame()
}
:::after
lines.clear()

loop {
  var f = FLine()

  while (stereo.controllerState().triggerValue > 0.0) {
    val at = stereo.controllerState().position
    f.lineTo(at + vec(0, 0, 0.1))
    lines["ourLine"] = f
  }

  frame()
}
```

Example static fence:

```diffstatic {data-lang=kotlin}
:::static
fun greet() {
  println("hello")
}
```

## Live demo
```diff {data-lang=kotlin}
:::before
lines.clear() // clear the list of lines to draw

loop { // do this forever (or until we stop this box)
  var f = FLine()    // make a new line for our drawing

  while(stereo.controllerState().triggerValue > 0.0) { // while the trigger is down
    var at = stereo.controllerState().position // get its position
    f.lineTo(at) // extend the drawing to this position
    lines["ourLine"] = f // put this line onto the screen under the name "ourLine"
    frame() // WAIT for a frame to come out of the screen
  }

  while(stereo.controllerState().triggerValue <= 0.0) { // while the trigger isn't down
    frame() // WAIT for a frame to come out of the screen
  }
}
:::after
lines.clear() // clear the list of lines to draw

loop { // do this forever (or until we stop this box)
  var f = FLine()    // make a new line for our drawing

  while(stereo.controllerState().triggerValue > 0.0) { // while the trigger is down
    var at = stereo.controllerState().position // get its position
    f.lineTo(at + vec(0,0,0.1)) // extend the drawing to this position
    lines["ourLine"] = f // put this line onto the screen under the name "ourLine"
  }

  while(stereo.controllerState().triggerValue <= 0.0) { // while the trigger isn't down
    frame() // WAIT for a frame to come out of the screen
  }

  frame() // WAIT for a frame to come out of the screen
}
```

## Static live demo
```diffstatic {data-lang=kotlin}
:::static
fun greet(name: String) {
  println("Hello, $name")
}

greet("Field4")
```

## Notes
- Set `data-language` to any highlight.js language key (e.g., `python`, `javascript`).
- For static blocks use only a `data-state="before"` block (or `data-state="single"`) to reuse the same styling without animation; the play control hides automatically.
- Multiple players on one page are supported; `mountAll()` binds everything with the `code-diff-player` class.
- The component lazy-loads highlight.js if it is not already present on the page.
- Source `<pre>` blocks are hidden after upgrade, so the page remains readable when JavaScript is disabled.

## Superfence shorthand
You can now author with fenced blocks and let the site JS convert them:

- Animated diff:

```diff {data-lang=kotlin}
:::before
fun hello() = println("hi")
:::after
fun hello(name: String) = println("hi $name")
```

- Static block:

```diffstatic {data-lang=kotlin}
fun greet() {
    println("hello")
}
```

These render into the same player automatically (via `fence-upgrade.js`), so you don’t have to write the HTML wrapper yourself.
