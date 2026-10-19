# Publish typed paint callbacks and clarify guides and remote shader lessons

Frozen findings: `ce11a168cbeb-01`, `153504d5e8c3-01`, `2792e161f146-01`, `2792e161f146-04`.

Studio publishes typed `studioCanvas` and `refreshStudio` callbacks; Scribbler and the tutorial use those callbacks instead of unchecked string-property casts. The canvas callback reads the current easel after replacement/rewind. The complete Studio/Scribbler transcript ran with the matching native paint library; a real tool call painted on a replacement canvas through the typed contract, and cleanup restored the original canvas.

The legacy video fragment now samples RGB into one opaque vec4 and retains its blue-screen discard threshold. Actual WebGL1 compiled both original and revised fragments and rendered the same texture: the sampled pixel is [64,128,32,255] and the keyed pixel remains transparent. Precision, uniforms and stage inputs remain compatible.

The ambiguous Kotlin template body is now an explicit JavaScript recipe reference shelf. Running it shows the original recipe source and identifies the neighbouring L-System box, its interpreter functions and remote scene prerequisites. This does not claim those JavaScript recipes run inside the Kotlin box. The original JavaScript is retained in its fenced reference.

Five complete interactive-guide states run with direct `thisBox` arguments. Navigation highlights line 11, which contains the link shortcuts; annotation line 17 still targets the actual selectBox call. A real Next link advanced the visible note; both annotation paths appeared and Monaco selected the verified line. The initial DOM check raced the editor's delayed selection; the corrected check waits on that actual state. Public captures were not regenerated. Their navigation alt text now describes the corrected source line; existing images retain historical line numbering pending normal docs recapture.

Actual Kotlin import/ktfmt, GLSL formatting, typed collection conversion and parsed code/literal/fragment checks passed. Full guards belong to the lander; no paid AI requests were made.

Checkpoint: 374/405 addressed, 31 open. Mechanical coverage: Actual import/ktfmt and GLSL formatter; parsed code, raw literals, fragment boundaries and prose verified. Full guards are delegated to the lander.
