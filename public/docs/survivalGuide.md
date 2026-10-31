# Survival Guide

_Field·4_ is a place for writing, running, debugging, understanding code — quickly. It's also a place for experimentation, making mistakes, getting frustrated, and delighting in discovery. This guide should help you get to the point where you have some muscle memory.

If you haven't run anything yet, start with [Your First Five Minutes](first_steps.md): it gives
you a drawing to change and brings you back here with a box you've already launched.

The cheat-sheet can be accessed by pressing the '?' key. Every shortcut on it is yours to change, and
the card shows whatever you have bound rather than these defaults — [Keyboard
Shortcuts](keybindings.md) is the whole story. Right now it looks like this:

![Field's ? cheatsheet card with the default bindings: Basics (N for a new box, select a box for its editor), Search and Help, Run (Opt-0 runs the whole box, Opt-click and Opt-Up launch, Opt-Down stops, Opt-Enter runs the current line), Editor, and Graph](assets/keybindings/cheatsheet.png)

The card holds the keys you need first; [the full list](keybindings.md#the-defaults-in-full) has
every command and its default shortcut.

Using these keyboard shortcuts, you should be able to write and execute some code in Field:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="760x560" clip="0,0,760,512" hide-toasts=true video="assets/survival_intro.mp4" fps="20" padding="0" wait="0" timeout="180" embed=true hidden=true name="survival-intro" alt="The mouse moves onto the empty canvas and n makes a new box there; a click selects it and opens its empty editor. A println of hello world is typed one key at a time and Alt-Enter runs that line, printing hello world underneath it. Two more lines are typed, val total = (1..10).sum() and println(total), and Alt-0 runs the whole box, printing 55 under the last line and hello world a second time (marked x2). Last, loop { display(frameNumber) } is typed and Alt-Up launches the box: it turns striped while it runs, and a click opens its output panel, where the frame count keeps climbing. A caption over the canvas shows each key and click as it happens."}
/*
Lay the editor pane out for this window, as a user's browser would: screenshot prep pins it
to a fixed size and place, which at this window size would cover the canvas. The fence hides
the toasts, which would otherwise stack over the canvas and the keypress caption.
*/
ui.eval(
    """
    const c = document.querySelector(".monaco-editor-container")
    if (!c) throw new Error("no editor container")
    for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
    if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
    return c.getBoundingClientRect().left
    """
        .trimIndent(),
)
ui.keypressDisplay(true)
ui.pause(200)
ui.moveMouse(170.0, 130.0, 12)
ui.pause(300)
ui.press("n")
ui.pause(900)
ui.moveMouse(180.0, 140.0, 4)
ui.clickAt(180.0, 140.0)
ui.pause(900)
ui.clickSelector(".monaco-editor")
ui.pause(400)
ui.typeSlowly("println(\"hello world\")", 70)
ui.press("Escape")
ui.pause(500)
ui.press("Alt+Enter")
ui.pause(1800)
ui.press("End")
ui.press("Enter")
ui.typeSlowly("val total = (1..10).sum()", 70)
ui.press("Escape")
ui.press("Enter")
ui.typeSlowly("println(total)", 70)
ui.press("Escape")
ui.pause(500)
ui.press("Alt+0")
ui.pause(2500)
ui.press("End")
ui.press("Enter")
ui.typeSlowly("loop { display(frameNumber) }", 70)
ui.press("Escape")
ui.pause(500)
ui.press("Alt+ArrowUp")
ui.pause(1200)
// The launched loop displays into the box's output channel, collapsed under it: open it.
ui.moveMouse(110.0, 200.0, 10)
ui.clickAt(110.0, 200.0)
ui.pause(4000)
val text = ui.eval("return document.querySelector('.monaco-editor-container').innerText").toString()
check("loop { display(frameNumber) }" in text.replace('\u00a0', ' ')) {
    "the intro did not type its loop: " + text.take(600)
}
check("hello world" in text && "55" in text) { "the intro's runs did not print hello world and 55: " + text.take(400) }
```

Press `f1` in the editor to search Field's commands, including the other ways to run code.

A box is one of the rectangles on the canvas, the area behind the editor; it holds the code you type into the editor. Field distinguishes between running code (a line or two, or the whole box) and _launching_ it. Run code executes once and finishes. Launched code can keep going: a `loop { }` in a launched box runs again on every frame of Field's animation loop, which is why the frame count at the end of the video keeps climbing until you stop the box (`alt-down`).

[Running code in a box](running_code.md) is what happens behind that distinction: what Field does
to your text before the compiler sees it, how a child box sees its parent's values, and where a
launched box hands over to the animation loop. You do not need it to start writing boxes.

## Saving and Loading

Field saves your document about every 5 seconds whenever something has changed.

Reloading the page reconnects you to Field but does not restart it: whatever you launched is still running.

To restart Field, press `ctrl-c` in the terminal you launched it from and start it again, or choose quit on the welcome page.

To change what document you are looking at, go back to the welcome tab — the `+` at the end of the tab strip opens one if you've closed it.

Documents are created on demand in your current workspace. Field reopens the workspace you used last.

## The command palette

For anything advanced pressing `f1` reveals a searchable command palette. This works both inside the editor and outside the editor — although you'll get a different set of commands in different situations. All kinds of things hide there — changing editor tabs, applying AI to code and error messages. Here it is opened from inside the editor:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-palette-shell" screenshot="assets/command-palette.png" padding="6" wait="0" timeout="90" embed=true hidden=true name="command-palette"}
ui.openEditor(id)
ui.pause(700)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.clickSelector(".monaco-editor")
ui.pause(300)
ui.press("F1")
ui.pause(1600)
```

Opened from the canvas instead, the palette offers commands that act on boxes. Two of them keep boxes for reuse: **Save selection as template** stores the selected boxes as a template, a named copy you can add to any document, and **Insert template** (`cmd+t`) adds one; see [Templates](templates.md).

For its main code-writing experience _Field·4_ wraps the [Monaco text editor](https://microsoft.github.io/monaco-editor/) — just like everyone else is doing these days — so there's a pretty rich set of ways of interacting with code built-in. But there are some advanced features to discover — inline printing, AI-chat (if you have an OpenAI Platform Key), number increment and decrement shortcuts, configurable code blocks.

## Autocomplete

One of the most useful keyboard shortcuts of all is autocomplete — `ctrl-space`. Use it to provide ideas about what you might type, and documentation about those things:

```diffstatic_runner {data-lang=kotlin capture="ui" region="editor" warm-completion=true screenshot="assets/autocomplete.png" wait="0" timeout="90" embed=true hidden=true name="autocomplete"}
ui.eval(
    "window.localStorage.removeItem('field.suggest.recency.v1'); var s=document.createElement('style');s.textContent='.tick_text.ok{display:none !important}';document.head.appendChild(s);return 'setup';",
)
ui.openEditor(id)
ui.pause(700)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.clickSelector(".monaco-editor")
ui.pause(300)
ui.press("Control+A")
ui.type("val axis = vec")
ui.pause(300)
ui.press("Control+Space")
/*
warm-completion=true built the completion engine up front, so the enriched RPC
result — the type-based completions and their KDoc — arrives promptly. Wait for
the documentation itself (not just the item) so the capture shows the doc panel.
*/
ui.waitForSelector("text=Creates a mutable", 15000.0)
ui.pause(1000)
```

Once you have turned on [Field's AI features](aiFeatures.md), this will include up to 3 AI-generated suggestions of what you might type next. These are more likely to be incorrect (in the sense that type-based completions are generally very, very good); but also more likely to be ambitious and lengthy. Our GLSL completions are, in particular, often exactly what you want.

Autocomplete answers "what can I write *here*". For the other question — what could I do here? — see [what's already in the box](reference/index.md), a browsable index of every public declaration in Field's standard library, generated from the same source and the same KDoc the completion panel reads.

## Next

Take [a Tour](funFeatures.md); if you're new to Kotlin, read the [Kotlin guide](kotlin_overview.md); learn what happens
when you [run code in a box](running_code.md); understand the [AI features](aiFeatures.md).
