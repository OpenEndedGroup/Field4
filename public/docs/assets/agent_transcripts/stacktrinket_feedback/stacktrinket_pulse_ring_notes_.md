# StackTrinket in Field

StackTrinket (`stacktrinket.html` in the madp classroom demos) is a live editor for **StackSL**, a
small Forth-like language in which every value on the stack is a `vec4`. A program is a list of
words. Each word takes its arguments off the top of the stack and pushes its result, and whatever
is left on top at the end is the colour of the pixel. The program runs for every pixel of the
window, every frame.

What StackTrinket adds to plain StackSL is **memory**. The frame drawn last time is bound as
`state`, so a program can read its own previous picture: `prev` reads it at this pixel, `sample`
reads it a little to the side, and `0->` … `3->` read one channel of it. Decay what was there,
add something new, and pictures build up over time into fog, trails, blooms and slow drifting
memories. The presets below are the page's feedback presets.

## How to play

1. Launch **renderer**, then this box (it is launched for you when the document opens).
2. Open this box's **stackSL** tab. That is the program. Edit it while it runs: each edit that
   compiles takes over on the next frame, and the picture carries on from where it was.
3. Run the box again to clear the picture and start from black.
4. Launch any other box to switch program, the way the page's preset menu does.
5. The **lowering** panel under the box says whether the program compiled, how deep the stack ends
   and which helpers it used, with any error at its line and column. Open the **glsl** panel to
   read the GLSL the program becomes.

Things to try here: change `0.5` to move the ring, `0.08` to soften it, `white` to `red`. Put
`prev 0.95 * +` at the end and the ring leaves a trail when you move it.

## Words to start with

| Word | Pushes |
| --- | --- |
| `uv` | `vec4(uv, 0, 1)`, 0..1 with y up |
| `p` | `uv` centred: `vec4(uv - 0.5, 0, 1)` |
| `time`, `0.2t` | seconds, or seconds times 0.2 |
| `prev` | the previous frame at this pixel |
| `x y vec2 sample` | the previous frame at an offset |
| `length`, `pulse`, `fbm`, `palette` | distance, a soft band, noise, a cosine colour |
| `dup`, `drop`, `swap` | copy, discard, swap the top values |

The full vocabulary is in Field's StackSL cheatsheet.

## How Field's version maps to the page

- The page passes `vec4(uv, time, hash)`; Field passes `vec4(uv, hash, time)`. The presets use the
  `time` and `hash` words, never the raw lanes, so they run unchanged.
- The page compiles with `stacksl.py`, a June 2026 port of Field's StackSL compiler. For these
  presets both produce the same GLSL line for line.
- Field requires a definition (`: name … ;`) to leave the stack as deep as it found it.
  `subroutine cse` breaks that rule on the page, so here each of its rings starts with `drop`.
- The page draws as often as the browser does, about 60 times a second. A feedback picture
  depends on that rate, because `prev 0.95 *` keeps 95% *each frame*. Field's window can draw much
  faster, so `stackFeedback()` steps the program 60 times a second (`fps`).
- The state is RGBA16F, linear and repeating, as on the page. The browser shows the program's
  numbers as they are, and Field's window expects linear light, so the window applies a 2.2
  display gamma (`displayGamma`).
- Not ported: the Sweep button (ten variants of the number under the cursor), local saves and the
  chat panel. Field's own documents, history and AI4 cover the last two.

## In code

`stackFeedback()` is the whole renderer: a float16 ping-pong target with the last frame bound as
`state`, a fullscreen pass that runs this box's `stackSL`, and the lowering panels. It returns the
running host, which has `reset()`, and settings such as `paused`, `fps` and `timeScale`
(`stackFeedback { fps = 30.0 }`).
