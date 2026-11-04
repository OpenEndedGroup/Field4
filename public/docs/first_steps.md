# Your first five minutes

Field opens on its Welcome page. Click **Start here: a moving drawing** at the top. Field copies
the example into your workspace as a document of its own. You can edit your copy freely. For this example, you won't need a GPU driver, Python or an AI key.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x900" clip-selector="#docs-first-welcome" screenshot="assets/first_steps/welcome.png" padding="0" wait="0" timeout="120" embed=true hidden=true name="first-welcome" alt="Welcome with the Start here: a moving drawing button at the top, beside the five-minute guide and Next: native graphics."}
// Capture the compact Welcome action row and the first-document prompt.
ui.eval(
    """
    const f = document.createElement("iframe")
    f.id = "docs-first-welcome"
    f.src = "/welcome"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1100px;height:850px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
    """
        .trimIndent(),
)
var ready = false
for (i in 0 until 60) {
    ui.pause(250)
    val state =
        ui.eval(
                """
                const d = document.getElementById("docs-first-welcome")?.contentDocument
                if (!d?.getElementById("open-first-steps")) return false
                const splash = d.getElementById("splash-screen")
                if (splash && !splash.classList.contains("hidden")) return false
                return [...d.querySelectorAll("details > summary")].every(s => !/loading|checking/i.test(s.textContent))
                """
                    .trimIndent(),
            )
            .toString()
    if (state == "true") {
        ready = true
        break
    }
}
check(ready) { "Welcome did not finish loading" }
ui.pause(1200)
```

The document opens as a canvas holding one box. A box is a titled rectangle that holds code;
when the code runs, what it draws or prints appears in the box's output area, just below it.
To *launch* a box is to run all of its code from the top. Code that keeps going, like this
drawing's animation loop, keeps running until you stop the box.

The recording shows the steps below: launch, change five petals to eight and run that line while
the drawing moves, launch again, and stop.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1200x760" clip="0,0,1200,720" hide-toasts=true video="assets/first_steps/first-look.mp4" macro-file="../../dev_docs/agents/transcripts/docs_capture/first_steps.yaml" capture-box="first_steps_drawing" fps="10" padding="0" wait="0" timeout="180" embed=true hidden=true name="first-look" alt="Field's real editor launches a cyan moving FLine drawing, changes its petal count from five to eight and runs that one line with Alt+Enter while the drawing keeps moving, relaunches it, and shows the value eight inline before stopping the animation."}
ui.eval("document.getElementById('docs-first-welcome')?.remove(); return true")
ui.openEditor("first_steps_drawing")
ui.eval(
    """
    const c = document.querySelector(".monaco-editor-container")
    Object.assign(c.style, {width:"620px", height:"710px", top:"0px", right:"0px", left:"auto", bottom:"auto", transform:"none"})
    window.globalEditor.layout()
    return true
    """
        .trimIndent(),
)
ui.clickSelector(".monaco-editor")
ui.keypressDisplay(true)
ui.press("Alt+ArrowUp")
ui.eval(
    """
    const label = document.querySelector('#keycast-overlay .keycast-text').textContent
    if (!['Alt+↑', '⌥↑'].includes(label)) throw new Error('launch chord was not displayed once: ' + label)
    return true
    """
        .trimIndent(),
)
ui.pause(2500)
ui.eval(
    """
    const box = [...document.querySelectorAll(".box")].find(b => b.uid === "first_steps_drawing")
    const host = [...box.querySelectorAll("*")].find(e => e.__disclosureAPI)
    if (!host) throw new Error("drawing output did not appear")
    const api = host.__disclosureAPI
    for (const id of api.ids()) { api.open(id); api.setSize(id, 350, 350) }
    const editor = window.globalEditor
    const match = editor.getModel().findMatches("\nvar petals = 5 ", false, false, false, null, true)[0]
    if (!match) throw new Error("the shipped five-petal declaration was not loaded")
    match.range = {startLineNumber: match.range.endLineNumber, endLineNumber: match.range.endLineNumber, endColumn: "var petals = 5".length + 1}
    editor.setSelection({startLineNumber:match.range.startLineNumber, startColumn:match.range.endColumn-1, endLineNumber:match.range.endLineNumber, endColumn:match.range.endColumn})
    editor.focus()
    return true
    """
        .trimIndent(),
)
ui.pause(2800)
ui.typeSlowly("8", 160)
ui.press("Escape")
ui.pause(700)
ui.press("Alt+Enter")
ui.pause(3500)
ui.press("Alt+ArrowUp")
ui.pause(3500)
ui.eval(
    """
    if (!window.globalEditor.getModel().getValue().includes("\nvar petals = 8 ")) throw new Error("the editor did not retain the edit")
    const outputs = [...document.querySelectorAll('.tick_text, .execution_output .inner_output')]
    if (!outputs.some(e => e.textContent.trim() === '8')) throw new Error("the real inline value eight did not appear")
    return true
    """
        .trimIndent(),
)
ui.pause(3500)
ui.press("Alt+ArrowDown")
ui.pause(1200)
```

1. Click the box titled **First steps · a moving drawing** (the one the Welcome button copied
   for you) to select it and open its code in the editor.
2. Press **alt/option-Up** to launch it. Open the output disclosure below
   the box if the drawing is collapsed. Fine blue-white curves ripple and twist together.
3. While it runs, change the 5 in `var petals = 5` (the second line) to 8, keep the cursor on that line
   and press **alt/option-Enter**. That runs only this line, and the running drawing picks up eight
   petals without restarting. Editing alone runs nothing; running the line does.
4. Press **alt/option-Up** to launch the whole box again: it starts over, still with eight petals,
   and `println(petals)` prints 8 beside the code. Press **alt/option-Down** to stop. The drawing freezes.
5. Wait for autosave (about five seconds), close the document tab, and reopen your copy from
   Welcome. The edited code is still there, but the drawing is not moving: opening a document
   does not restart its animations. Launch it again to bring it back.

If a window manager intercepts the shortcuts, use Field's editor command palette (**ctrl/command-shift-P**) to
find the launch and stop commands. Press **?** on the canvas for the shortcut card.

The drawing is made of `FLine`s, Field's type for drawn lines and curves, drawn into the box's output, so it needs no separate graphics
window. Its loop computes a ribbon from `petals` and `phase`. Each layer follows the same wave
with a small delay, making the lines bunch together and spread apart. The code is in the document,
including the comments that walk through these steps.

The fine-line motion takes inspiration from [Étienne Jacob’s animation gallery](https://bleuje.com/animationsite/2024_1/).

Try another edit: change `petals` again while the drawing runs. The line you run becomes part of
the state the loop is already using. That's a useful way to work in Field: keep the thing you're
making alive while you change it.

## Try a native graphics window { #next-a-native-graphics-window }

The drawing above runs in a box's output panel. If you'd like to try a separate GPU graphics
window too, the next starting point takes you there. You can skip it and continue to the
[Survival Guide](survivalGuide.md) if you want to stay with the basics for now.

Return to Welcome and click **Next: native graphics**. This opens an editable `first-graphics`
copy with two connected boxes. The window draws with Vulkan, the GPU graphics system Field uses,
so first open the **Vulkan** panel on the Welcome page and check that it finds a working GPU
driver. You also need a desktop session for the window to appear in.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x720" clip="0,0,1100,600" screenshot="assets/first_steps/first-graphics.png" macro-file="../../dev_docs/agents/transcripts/docs_capture/first_graphics.yaml" capture-box="first_graphics_renderer" padding="0" wait="0" timeout="120" embed=true hidden=true name="first-graphics-document" alt="The first-graphics document has two connected boxes: 1 · Launch the renderer above 2 · Launch the triangle. The renderer's Kotlin is open on the right."}
ui.openEditor("first_graphics_renderer")
ui.eval(
    """
    const c = document.querySelector(".monaco-editor-container")
    Object.assign(c.style, {width:"580px", height:"350px", top:"30px", right:"0px", left:"auto", bottom:"auto", transform:"none"})
    window.globalEditor.layout()
    const boxes = [...document.querySelectorAll('.box')]
    for (const uid of ['first_graphics_renderer', 'first_graphics_triangle']) {
        if (!boxes.some(b => b.uid === uid)) throw new Error('missing template box: ' + uid)
    }
    return true
    """
        .trimIndent(),
)
ui.pause(800)
```

1. Click the **1 · Launch the renderer** box to select it and open its code. Press **alt/option-Up**.
   A separate, initially black graphics window opens.
2. Return to Field, click the **2 · Launch the triangle** box, and press **alt/option-Up**.
   The graphics window now shows a colored triangle.
3. To stop, select the triangle box and press **alt/option-Down**.
   Then select the renderer box and press the same shortcut.

The renderer box's code opens the window and creates the renderer. The triangle box's code lists
the triangle's three corners and their colors. The triangle box also holds two shaders, small
programs that run on the GPU: `vertex` places each corner and `fragment` colors the pixels between
them. Switch the editor from `code` to `vertex` or `fragment` to read them. The
[graphics curriculum](afield_four_graphics.md) builds on this pair step by step. With both boxes running, the window looks like this:

```diffstatic_runner {capture-profile="linux-nvidia" data-lang=kotlin capture="native-window" native-wait-frames="4" trim-native=true screenshot="assets/first_steps/triangle.png" macro-file="../../dev_docs/agents/transcripts/docs_capture/first_graphics_native.yaml" capture-box="first_graphics_triangle" wait="0.25" timeout="120" embed=true hidden=true name="first-graphics-triangle" alt="The first native graphics window draws a blue-to-green triangle on black."}
// The setup macro launches both boxes with their shipped code and shader properties.
```

When the triangle runs, [Graphics on the GPU](tour_gpu_graphics.md) goes on to shaders you
edit while the window draws.

## Where next?

From here, the [Survival Guide](survivalGuide.md) gives you the moves you'll use every day. To
make more drawings like the first one, go to [Drawing with FLines](fline_examples.md). To learn
where `println` and `display` put their results, see [Box Output](box_output.md); for where your
work and AI history are kept, see [Your code, AI and local history](privacy.md).
