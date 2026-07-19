# Slideshow annotations and text animation

Authoring examples are in the annotations section of `public/docs/interactive_guides.md`. Builder
signatures and defaults belong on `annotate`, `annotateToLine`, and `note` in
`src/jvmMain/kotlin/afield/SlideShow.kt`. Rendering behavior is documented at the top of
`src/jsMain/kotlin/afield/slideshowSupport.kt`.

Implementation invariants:

- annotation `id` is the stable interpolation-state key; every line starts from the note overlay as
  a whole and targets either a box or Monaco line region;
- paths are clipped, resampled Dubins curves with a straight fallback and an arrow head;
- redraw follows note animation, editor visibility/property changes, scrolling, drag/resize, and
  overlay movement;
- changing/stopping a step clears both SVG nodes and stored interpolation paths;
- animated notes preserve markup and reveal text-node characters, with punctuation pauses.

There is no dedicated annotation UI regression today. Verify against real DOM geometry at more than
one canvas zoom/translation, with Monaco both visible and hidden, and while the target moves. Styling
examples and speculative future features do not belong in this developer contract.
