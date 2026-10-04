# Your first five minutes

On Welcome, click **Start here: a moving drawing**. Field copies the bundled example into your
workspace as `first-steps` (or `first-steps-2` if that name is already taken). Your copy is editable;
opening it never changes the shipped example. No GPU driver, Python or AI key is needed.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x900" clip-selector="#docs-first-welcome" screenshot="assets/first_steps/welcome.png" padding="0" wait="0" timeout="120" embed=true hidden=true name="first-welcome" alt="Welcome with the Start here: a moving drawing button at the top, beside the five-minute guide and Next: native graphics."}
// Capture the current Welcome header and its full-width button rows.
ui.eval("""
    const f = document.createElement("iframe")
    f.id = "docs-first-welcome"
    f.src = "/welcome"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1100px;height:850px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
""".trimIndent())
var ready = false
for (i in 0 until 60) {
    ui.pause(250)
    val state = ui.eval("""
        const d = document.getElementById("docs-first-welcome")?.contentDocument
        if (!d?.getElementById("open-first-steps")) return false
        const splash = d.getElementById("splash-screen")
        if (splash && !splash.classList.contains("hidden")) return false
        return [...d.querySelectorAll("details > summary")].every(s => !/loading|checking/i.test(s.textContent))
    """.trimIndent()).toString()
    if (state == "true") { ready = true; break }
}
check(ready) { "Welcome did not finish loading" }
ui.pause(1200)
```

1. Select **First steps · a moving drawing** to open its code.
2. Press **Alt+Up** to launch it. On macOS, use **Option+Up**. Open the output disclosure below
   the box if the drawing is collapsed. A cyan curve moves as its phase changes.
3. Change `var petals = 5` to `var petals = 8`, then press **Alt+Up** again. The shape changes;
   `println(petals)` shows the actual value beside the code. Editing alone does not evaluate code.
4. Press **Alt+Down** (**Option+Down** on macOS) to stop. The drawing freezes.
5. Wait for autosave (about five seconds), close the document tab, and reopen your copy from
   Welcome. The edited code is still there. Launch it to animate again: saved code is persistent;
   a running animation is not automatically restarted by opening the file.

If a window manager intercepts the shortcuts, use Field's editor command palette (**F1**) to
find the launch and stop commands. Press **?** on the canvas for the shortcut card.

The drawing is a real `FLine`, rendered into the browser output. Its loop computes a fresh path
from `petals` and `phase`; no native Vulkan window is involved. The code is in the document,
including the comments that walk through these steps.

## See it happen

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1200x760" clip="0,0,1200,720" hide-toasts=true video="assets/first_steps/first-look.mp4" macro-file="../../dev_docs/agents/transcripts/docs_capture/first_steps.yaml" capture-box="first_steps_drawing" fps="20" padding="0" wait="0" timeout="180" embed=true hidden=true name="first-look" alt="Field's real editor launches a cyan moving FLine drawing, changes its petal count from five to eight, relaunches it, and shows the value eight inline before stopping the animation."}
ui.openEditor("first_steps_drawing")
ui.eval("""
    const c = document.querySelector(".monaco-editor-container")
    Object.assign(c.style, {width:"620px", height:"710px", top:"0px", right:"0px", left:"auto", bottom:"auto", transform:"none"})
    window.globalEditor.layout()
    return true
""".trimIndent())
ui.keypressDisplay(true)
ui.clickSelector(".monaco-editor")
ui.press("Alt+ArrowUp")
ui.pause(2500)
ui.eval("""
    const box = [...document.querySelectorAll(".box")].find(b => b.uid === "first_steps_drawing")
    const host = [...box.querySelectorAll("*")].find(e => e.__disclosureAPI)
    if (!host) throw new Error("drawing output did not appear")
    const api = host.__disclosureAPI
    for (const id of api.ids()) { api.open(id); api.setSize(id, 350, 350) }
    const editor = window.globalEditor
    const match = editor.getModel().findMatches("var petals = 5", false, false, false, null, true)[0]
    if (!match) throw new Error("the shipped five-petal code was not loaded")
    editor.setSelection({startLineNumber:match.range.startLineNumber, startColumn:match.range.endColumn-1, endLineNumber:match.range.endLineNumber, endColumn:match.range.endColumn})
    editor.focus()
    return true
""".trimIndent())
ui.pause(2800)
ui.typeSlowly("8", 160)
ui.press("Escape")
ui.pause(700)
ui.press("Alt+ArrowUp")
ui.pause(3500)
ui.eval("""
    if (!window.globalEditor.getModel().getValue().includes("var petals = 8")) throw new Error("the editor did not retain the edit")
    const outputs = [...document.querySelectorAll('.tick_text, .execution_output .inner_output')]
    if (!outputs.some(e => e.textContent.trim() === '8')) throw new Error("the real inline value eight did not appear")
    return true
""".trimIndent())
ui.pause(3500)
ui.press("Alt+ArrowDown")
ui.pause(1200)
```


## Next: a native graphics window

Return to Welcome and click **Next: native graphics**. This opens an editable `first-graphics`
copy with two connected boxes. Check Welcome's **Vulkan** panel first: you need a working GPU
driver and desktop session.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x720" clip="0,0,1100,600" screenshot="assets/first_steps/first-graphics.png" macro-file="../../dev_docs/agents/transcripts/docs_capture/first_graphics.yaml" capture-box="first_graphics_renderer" padding="0" wait="0" timeout="120" embed=true hidden=true name="first-graphics-document" alt="The first-graphics document has two connected boxes: 1 · Launch the renderer above 2 · Launch the triangle. The renderer's Kotlin is open on the right."}
ui.openEditor("first_graphics_renderer")
ui.eval("""
    const c = document.querySelector(".monaco-editor-container")
    Object.assign(c.style, {width:"580px", height:"350px", top:"30px", right:"0px", left:"auto", bottom:"auto", transform:"none"})
    window.globalEditor.layout()
    const boxes = [...document.querySelectorAll('.box')]
    for (const uid of ['first_graphics_renderer', 'first_graphics_triangle']) {
        if (!boxes.some(b => b.uid === uid)) throw new Error('missing template box: ' + uid)
    }
    return true
""".trimIndent())
ui.pause(800)
```

1. Click the **1 · Launch the renderer** box to select it and open its code. Press **Alt+Up**
   (**Option+Up** on macOS). A separate, initially black graphics window opens.
2. Return to Field, click the **2 · Launch the triangle** box, and press **Alt+Up**
   (**Option+Up** on macOS). The graphics window now shows a colored triangle.
3. To stop, select the triangle box and press **Alt+Down** (**Option+Down** on macOS).
   Then select the renderer box and press the same shortcut.

The Kotlin code and GLSL shader properties come from the worked
[graphics curriculum](afield_four_graphics.md).


```diffstatic_runner {capture-profile="linux-nvidia" data-lang=kotlin capture="native-window" native-wait-frames="4" trim-native=true screenshot="assets/first_steps/triangle.png" macro-file="../../dev_docs/agents/transcripts/docs_capture/first_graphics_native.yaml" capture-box="first_graphics_triangle" wait="0.25" timeout="120" embed=true hidden=true name="first-graphics-triangle" alt="The first native graphics window draws a blue-to-green triangle on black."}
// The setup macro launches both boxes with their shipped code and shader properties.
```

Continue with the [Survival Guide](survivalGuide.md), [Drawing with FLines](fline_examples.md),
or [Your code, AI and local history](privacy.md).
