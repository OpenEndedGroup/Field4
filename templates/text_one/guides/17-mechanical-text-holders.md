# Mechanical text holders

Examples **10–11** add eight straight outward extensions to the corners of a text
frame's overall bounding rectangle. Examples **12–13** put text on the two fitted
ends of a wireframe rectangular frustum. All four use excerpts from the source passage,
additive FLine strokes, the ordinary Slug renderer and the existing frost/blur passes.

## Eight outward extensions

```kotlin
val border = textFrame("holder", words, standoff = .025f,
    mode = DiagramTextFrameMode.LINE_UNION,
    overstrokeFraction = .12f,
    overstrokeDistance = .015f,
) {
    width = .0007f
    color = vec(1f, 1f, 1f, .18f)
}
border.frost.apply { enabled = true; blurPixels = 8f }
```

For a bounding side of length `L`, the extension at **each** end has length
`L * overstrokeFraction + overstrokeDistance`. The fraction is dimensionless;
the constant is in metres. Zero for both leaves the original frame unchanged.
Both values remain writable on `border` after construction.

There are two outward extensions per side, eight in total. They belong only to the
four sides of the **overall** bounds. A stepped union retains its exposed outline;
its individual rectlets and interior seams never generate extension strokes.

These marks decorate the outline. They do not enlarge the frosted polygon or
change `border.attach()` and its sliding perimeter. Text rotation, current physics,
font scale and scored movement affect the outline and decoration together.

```kotlin
val score = diagramScore(graph, 28.0) {
    val border = graph.textFrame("holder")
    fadeIn(border, at = 1.0, seconds = 3.0)
    overstroke(border, fromFraction = .12f, toFraction = .25f,
        at = 7.0, seconds = 8.0,
        fromDistance = .015f, toDistance = .025f)
    fadeOut(border, at = 22.0, seconds = 5.0)
}
```

The overstroke cue is absolute and seekable. It changes the evaluated pose, leaving
the authored values untouched. Its values override the corresponding authored
parameters while this score is in use; remove the cue to control them directly.

## Two frosted ends and four rails

```kotlin
val front = text("front", "Marble.\nGoing back to antiquity.",
    point("near", 0, 0, .2)) {
    font = face
    scale = .72f
    blurCapacity = .8f
}
val back = text("back", "Faintly hinted,\nthe heady scent of a rose.",
    point("far", .08, .04, -.2)) {
    font = face
    scale = .44f
    blurCapacity = .8f
}
val holder = textFrustum("holder", front, back, standoff = .025f) {
    aspectRatio = 3.2f
    width = .0007f
    color = vec(1f, 1f, 1f, .17f)
    frost { enabled = true; blurPixels = 7f }
}
```

The two runs keep their ordinary physics anchors, orientation, style and blur.
Their separation sets the holder's depth; their fitted sizes set its taper.
A common aspect expands either rectangle as necessary to fit all its text. Set
`aspectRatio` to a positive value, or leave it `null` to follow the front run's
natural padded aspect. It never compresses glyphs or clips text to force a fit.

Parallel end planes make a rectangular pyramid frustum. Independently rotating
an end makes a skewed holder. The four rails connect corresponding outer corners;
a reversed back-facing label does not twist the rails across the centre.

`frontFrame` and `backFrame` expose the normal frame API, including `frost`,
`attach()`, `standoff`, `overstrokeFraction` and `overstrokeDistance`. Each end is an
independent frosted surface and uses the existing capture budget. The rails are a
normal `DiagramPath` at `holder.rails`. `holder.width` and assigning `holder.color`
style all structural lines; the text styles remain independent.

Example 12 keeps both runs facing the same way, to read through the transparent
holder. Example 13 rotates the back run by PI around Y so it reads from outside
that end. Walk around the holder to read its second face.

## Score the assembly or its parts

```kotlin
val score = diagramScore(graph, 30.0) {
    val holder = graph.textFrustum("holder")
    fadeIn(holder, at = 0.0, seconds = 4.0)
    blurIn(holder, at = 0.0, seconds = 7.0, radius = .6f)
    move(holder, vec(0f, 0f, 0f), vec(.06f, .03f, .02f), at = 8.0, seconds = 8.0)
    blurOut(holder, at = 21.0, seconds = 7.0, radius = .6f)
    fadeOut(holder, at = 24.0, seconds = 5.0)
}
```

Whole-holder fades include both texts, both frames and all four rails. Whole-holder
blur affects its two text runs. Whole-holder movement offsets the two text ends
once; frames, frosting, rails and attachments follow their current positions.
Use `transition(firstHolder, secondHolder, at = ..., seconds = ...)` for a
whole-holder crossfade of complete lines. The named selection is also available
as `graph.group("holder")`.

For an opening/tapering cue, score `move(holder.back, ...)` and
`resize(holder.back, ...)`. These are the same ordinary text pose channels used
elsewhere. Rail endpoints and fitted/frosted end rectangles follow automatically.
All four examples fade complete lines rather than using draw-in tracks.

Global looks also retain each frame's authored margin and overstroke settings,
and each holder's fixed/automatic aspect choice. Loading an older look leaves
new, previously absent parameters at their authored values.
