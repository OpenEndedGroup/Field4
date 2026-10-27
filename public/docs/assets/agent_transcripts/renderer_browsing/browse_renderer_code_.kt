// `renderer` is the parent box's Renderer. column(...) opens it as Finder-style columns:
// render (the main target, its passes in draw order), compute, windows, cpu (CPU span timings),
// device. Each node with children starts with a `summary` row: its card.
column("renderer", renderer, width = 1100)

// The same value as an indented tree.
tree("renderer tree", renderer)
