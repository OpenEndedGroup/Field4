# Typed, inherited shader source

Baseline `a9d60a2fd`. Five findings addressed across the neural reconstruction example/transcript,
two OpenDLSS study assets and the published OpenDLSS guide. Construction and reload use inherited
vertex/fragment properties with explicit required-value diagnostics. Reload reads remain inside
the reload action. Renderer/study captures, cleanup and failed-reload reporting remain intact.
The distinct optional prior-local-lab cleanup remains a deliberate raw lookup.

[Reviewed edits](changes.json), [source proof](verification.json), [rehearsal](rehearse.py), and
[evidence](evidence.tar.gz) record the changes and results. The actual editor import pass and ktfmt
cover 21 code bodies, including ten guide states; one collection factory becomes a literal. The
arbitrary example.kt is formatted through a temporary eligible sidecar and copied back. Parsed
container, body-token, fragment, raw-string and GLSL-comment/token checks pass. The idiom checker
covers its three eligible files; the additional two YAML asset containers are checked structurally.

The real graph-building/compile-only path compiles all six selected graphs: the neural transcript,
two study assets, two full published guide bodies and the standalone example in a renderer parent
graph. No graphics resources are evaluated. A real parent/child macro then checks inherited vertex
and fragment strings, changes the parent's vertex source, and checks that the next child read sees
the update. Aliases stay within one macro run. The first harness failures (fold directives in the
published source and aliases split across macro runs) are preserved alongside the repaired results.
No new GPU/driver availability, reconstruction quality or rendered-image acceptance is claimed.
