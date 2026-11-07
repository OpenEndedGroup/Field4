# Motion, scores and the small physics graph

The particle graph supplies a gently moving support. The score supplies deliberate phrasing.
Neither layer owns the headset camera: rendering continues with the current eye poses even
when the poem is paused.

## Keep physical state sparse

One particle can carry a complete sentence and all of its local decorations. Add particles
when separate pieces need to move independently, not for every glyph. SIMDVerlet supports
up to four reciprocal links per particle in this reading engine.

```kotlin
val graph = readingDiagram {
    val peg = point("peg", 0, .4, pinned = true)
    val first = point("first", -.25, .1, .02)
    val second = point("second", .25, -.1, -.02)
    link(peg, first, strength = .4f)
    link(first, second, strength = .35f)
    text("first-line", "a held breath", first)
    text("second-line", "a small release", second)
}
```

A physics link is invisible. Add a drawn line or arc separately if the connection should be
visible. Pinned points stay at rest; layout springs pull the other points toward their
rest configuration. Constraint strength, gravity, wind and velocity damping have separate roles.
Physics uses fixed 1/120-second steps, with bounded catch-up work.

For a still proof, set the player's `gravity` and `wind` to zero, call `resetPositions()`,
and pause playback. Text geometry and headset pose remain current. Resetting positions clears
Verlet displacement; it does not recompile text or reset the compositor.

## Write the score separately

```kotlin
val score = diagramScore(graph, duration = 12.0) {
    enter(graph.text("first-line"), at = 0.0, seconds = 1.2)
    enter(graph.text("second-line"), at = .4, seconds = 1.2)
    exitAll(at = 9.0, seconds = 2.0)
}
player.playback.score = score
```

The graph may also have named paths and dots:

```kotlin
val score = diagramScore(graph, duration = 12.0) {
    drawIn(graph.path("rule"), at = .6, seconds = 1.5)
    enter(graph.dot("bead"), at = 2.1, rise = 0f)
    drawOut(graph.path("rule"), at = 8.0, seconds = 1.5)
}
```

The second example assumes those named items exist in your graph. `drawIn` advances a reveal
fraction; `drawOut` advances an erase fraction. `fadeIn` and `fadeOut` affect opacity.
`move` adds a metre-space offset and `resize` multiplies text/marker scale. Multiple tracks
compose on a fresh pose at each sampled time; seeking backward does not accumulate transforms.

For direct motion control:

```kotlin
val score = diagramScore(graph, duration = 12.0) {
    move(graph.text("first-line"), vec(0f, -.03f, 0f), vec(0f, 0f, 0f), at = 0.0, seconds = 1.0)
    resize(graph.text("first-line"), from = .95f, to = 1f, at = 0.0, seconds = 1.0)
}
```

Drawing attached through text-local measurements follows that motion automatically. A
baseline rule should not need a second, manually matched animation.

## Groups and transitions

Create groups while constructing the diagram. They are named selections, not extra transforms
or physics objects. A score can call `transition(fromGroup, toGroup, at, seconds)` to crossfade
between structural furniture and a reading line, as the fourth example does.

Do not turn off the render loop to create a hold. Pause the score or hold its sampled time;
head motion still needs fresh stereo views. Score time is absolute and seekable, while physics
is stateful. To reproduce an exact still layout, reset the particle state as well as seeking.

## A useful working order

1. Show the complete diagram and make the composition readable.
2. Set physics still and verify every measurement and attachment.
3. Animate one text entrance, then its supporting drawing.
4. Time beads and labels deliberately; their timing is independent of stroke reveal.
5. Add a small amount of physical motion after the score is coherent.
6. Check in the headset: spacing, faint lines and small grammatical labels need actual viewing.
