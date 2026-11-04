# Capture the all-code sections of afield_four_graphics

- **Handoff:** `fd507fbf-6c04-40ea-aece-ccc980796f4e` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** agent:claude, 2026-10-31 14:11
- **Close with:** `python tools/docs_notes.py handoff-close fd507fbf "what happened"`

## The notes that led here

- `1e68e1ce` (public/docs/afield_four_graphics.md:392): we must have a screenshot in this section; maybe even an mp4
  > Compute particles, then draw them
- `c1485024` (public/docs/afield_four_graphics.md:394): let's not talk about two boxes being connected without an actual screenshot of two boxes being connected. How would a user know? Also, link to the docs on connecting boxes and `prop`
  > The compute example above dispatched a shader and read the result back on the CPU. The more useful pattern keeps the data on the GPU: one box computes into a buffer, a second box draws from that same buffer, and the two are wired together through the box graph rather than through a shared global.
- `175ecf9c` (public/docs/afield_four_graphics.md:695): disappointed that this screenshot is basically the same as the previous one; also, same awful code formatting
  > prop<Array<ImageView>>(ta) is the whole difference. Note that LoadedTexture is not itself the bindable resource; bbc.imageView is, which is why the array is built from image views rather than from the textures. All three entries here point at the same image file, so the figure looks exactly like the texturing one: same quad, same texture. Swap in different files to see the array at work.
- `dd509af0` (public/docs/afield_four_graphics.md:801): No! just show the damn screenshot.
  > Neither carries a figure: these saved lessons use an external glTF file on disk. The paths above point at the glTF Sample Models collection, which the saved lessons were written against. Their paths are relative to the directory Field runs from, so clone that collection into your Field checkout or point the paths at your own copy. If you want geometry from disk with assets that are in the tree, see Geometry from disk.
- `ae6813a4` (public/docs/afield_four_graphics.md:905): screenshot or it didn't happen
  > A target you can read and write in the same pass is a contradiction, so feedback needs two images with the roles swapped every frame. PingPongRenderToTexture does that bookkeeping:
- `9e426700` (public/docs/afield_four_graphics.md:950): we have a screenshot of this? This is crazy, this is supposed to be a graphics page and it's all code.
- `755baf30` (public/docs/afield_four_graphics.md:1468): SCREENSHOT, jesus
  > The last lesson is the shortest, because the work is packaged:
- `e51a3a58` (public/docs/afield_four_graphics.md:1527): screenshot!
  > In the Biham–Middleton–Levine model every cell of a torus is empty or holds one car. Red cars only move right, blue cars only move down, and they take turns: on even steps every red car with an empty cell ahead moves into it, on odd steps the blue cars do the same. The only parameter is how many cars there are. Run this in a box beneath the renderer box:
- `a3271bd1` (public/docs/afield_four_graphics.md:1560): animation of a field probe?
  > The window shows the pattern but not how much of the traffic is moving. A field probe can tell you. Set probe to a GLSL expression and the system calls fieldProbe(...) with it at the commented line above, which runs exactly once for each car that moves. Here it records x + y of the cell the car is leaving:

Marc's notes on `public/docs/afield_four_graphics.md` ask for real captures in the sections that are still all code: "this is supposed to be a graphics page and it's all code." Each one needs a runner fence wired to a lesson (or a transcript) and a capture that comes out right, which is capture-system work, not a prose edit.

Sections that need a figure (headings in `public/docs/afield_four_graphics.md`):

- **Compute particles, then draw them**: a screenshot, ideally an MP4 of the particles moving. Marc also asks that the page not talk about two connected boxes without showing them connected, so this section wants a canvas capture of the compute box with the draw box beneath it too (with links to `tour_connecting_boxes.md` and `reactive_properties.md` for `prop`). `dev_docs/agents/transcripts/threebox.yaml` builds the same renderer/compute/draw shape and could drive both captures.
- **Multi-texture**: the figure looks the same as the texturing one because all three array entries load the same image file. The lesson should load three different images, and the capture should show that (for example, three quads each sampling a different `ta[i]`).
- **Loading a glTF model**: "just show the damn screenshot." The lessons read the external glTF Sample Models collection. Options: ship a small CC0 glTF in the tree (in `public/docs/assets/`) and point the lesson at it, or fetch a pinned sample the way `buildDocsNativePrerequisites` fetches Dirt-Samples. After that, the paragraph beginning "Neither carries a figure" goes.
- **Ping-pong render to texture**: "screenshot or it didn't happen." Needs a two-box capture (producer plus consumer).
- **A blur kernel across three boxes**: a three-box chain (`window → 5. Bluring → 5. kernel → 5. draw blur`); capture the final window and, if possible, the box chain on the canvas.
- **Diffusion-limited aggregation** (`ShaderDlaSystem`) and **Traffic: the Biham–Middleton–Levine model** (`ShaderBmlSystem`): each is one box beneath the renderer, so a `native-window` runner fence like the triangle ones should be enough. An MP4 would show the dynamics better.
- **Probing the cars that move**: an animation of the field probe's display updating as the BML traffic runs.

Proposed approach: turn each `diffstatic` fence into a `diffstatic_runner` with `capture="native-window"`, or add a `macro-file` transcript for the multi-box ones (like the first triangle's `afield_four_traditional_triangle.yaml`). Where a box graph matters, add a second `capture="ui"` canvas capture. The alternative is a gallery-style side preview (see the site handoff), which helps with code but is not a picture.

Questions for the human:

- Screenshot or MP4 for particles, DLA, BML and the probe?
- For glTF: ship a small model in the tree, or fetch a pinned one at docs-prerequisite time?
- Should multi-box sections show the canvas as well as the window?

Once this is done, the page needs the "no screenshot for this section" sentences removed, and the closing paragraph in "Where these examples come from" ("Sections without an image say why…") updated.
