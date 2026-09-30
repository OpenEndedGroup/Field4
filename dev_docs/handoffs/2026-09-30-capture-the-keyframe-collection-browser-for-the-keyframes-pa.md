# Capture the keyframe collection browser for the keyframes page

- **Handoff:** `56e43093-ccb8-47c0-91c0-f4418cfd8f2a` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** agent:claude, 2026-09-30 20:42
- **Close with:** `python tools/docs_notes.py handoff-close 56e43093 "what happened"`

## The notes that led here

- `1ae7ae30` (public/docs/keyframer_examples.md:3): do we not have a picture of the wonderful keyframe panel?
  > Every keyframer.kt declaration used below is listed, with its KDoc, in the codebase reference: keyframer.kt. The two supporting types the examples lean on, afield.four.buffers.Structure and afield.four.util.CameraPose, are not: they live in afield.four, which the reference leaves to afield.four Graphics.

## What was asked

On the opening paragraph of `public/docs/keyframer_examples.md` the note asks: "do we not have a
picture of the wonderful keyframe panel?"

## The answer, and why this is more than a prose edit

No. None of the pages under `public/docs/` has a still of the keyframe collection browser
(`Box.keyframeBrowser`, `src/jvmMain/kotlin/afield/outputs.kt:859#fun Box.keyframeBrowser(`, page
`src/commonMain/resources/keyframe_browser.html`, server side
`src/jvmMain/kotlin/afield/server/keyframeBrowsing.kt`). The page never shows a `keyframeBrowser(...)` call
either. It mentions the browser only in passing, at its **Code blend** button ("Seeing what it
decided", near the end). The only uses in the docs tree are the `keyframe_key_discovery` agent
transcript (`public/docs/assets/agent_transcripts/keyframe_key_discovery/*.kt`), which is only
reachable through an offline-viewer link in `agent_transcripts.md:1431`.

A picture has to be a real capture. That means a new `diffstatic_runner` fence that saves a few keys,
registers a scope and calls `keyframeBrowser(...)`, captured with `capture="box"`. The panel is an
iframe (`keyframeBrowserIframe`, `outputs.kt:886`) that loads its data from `/keyframe_browser.html`
asynchronously, with `data-afield-autosize="off"`. Nobody has checked yet whether the runner's box
screenshot waits for the iframe's contents. This partition cannot run the docs build to find out, and
an unverified new capture could break the harness's verification of the whole pass.

## Proposed approach

Add a short section after "Saving: every save is history", or fold it into that section, since the
browser browses exactly those saved files:

```kotlin
import afield.four.buffers.Structure
import afield.stdlib.KeyframeScope
import java.nio.file.Files

val dir = Files.createTempDirectory("keyframer-browser")
val glow = Structure(); glow.bind("radius") { 4f }; glow.bind("intensity") { 0.35f }
val scope = KeyframeScope()
scope.target("glow@radius", glow); scope.target("glow@intensity", glow)
for ((name, r, i) in listOf(Triple("soft", 3f, 0.2f), Triple("hot", 9f, 0.8f), Triple("mid", 6f, 0.5f)))
    scope.saveKey(dir.resolve(name), scope.Key(mutableMapOf("glow@radius" to r, "glow@intensity" to i)))
keyframeBrowser(dir, scope = scope, height = 380)
```

Capture it with `capture="box" open-outputs="true"` and a wait long enough for the iframe to fill.
If the screenshot comes out blank, the fix belongs in the capture system (wait for the iframe's
load or its first data response), not in a stand-in image. Reusing a transcript capture from
`keyframe_key_discovery` is the alternative. Those boxes demonstrate browser bugs (pattern
narrowing, junk against damage), not the ordinary view.

## Questions for a human

- Where should the panel go: its own section ("Browsing saved keys"), or at the top of the page as
  the lead picture the note seems to want?
- Should the figure show the scope-only "never saved" row and a live Apply button? That needs
  `scope =` to be passed.
- Is a still enough, or should it be a `capture-ui` video of a click on Apply that moves a value?

## What the page needs once done

The new fence and figure, a sentence introducing `keyframeBrowser(dir, scope = …)`, and the
passing mention of the browser under "Seeing what it decided" should link back to that section.
