# Frosted reading planes

Box **12 · Drawing / score playback**, **frosted frames**, controls the frost behind
frames in examples 7 and 8. Start with sigma 5 pixels and brightness 1.1. The text and
its own frame stay sharp; only geometry behind the frame is blurred. The Field browser
and body-debug overlays remain sharp.

Every `textFrame` owns independent settings:

```kotlin
val border = textFrame("paragraph-frame", paragraph,
    standoff = .025f, mode = DiagramTextFrameMode.LINE_UNION)
border.frost.apply {
    enabled = true
    opacity = 1f
    blurPixels = 5f
    brightness = 1.1f
    blackLevel = .012f // minimum linear red/blue light; zero preserves black
    clipEpsilon = .002f // metres behind the panel; exclude its own outline and text
}
```

The frosted silhouette is the exact padded union used by the outline and sliding
connection endpoints. Its exposed edges supply a spatially antialiased mask; internal
rectlet seams do not become visible. It follows the current text orientation, physics
position, typography and score opacity. Score fade-out fades the correction as well.

## Replacement made of additive light

Each visible panel captures the ordinary scene, clipped behind its plane, into a
full-resolution two-eye float16 RTT. Two half-resolution passes apply a separable
Gaussian blur. The panel then adds this signed correction:

```
opacity × coverage × (brightness × blurredBehind − sharpBehind)
```

The main scene and correction share one frame, predicted eye pose and command buffer.
No image from an earlier head pose is reused. Foreground geometry, the panel's own
ink and text are excluded from the capture. The green control channel is blurred
without the brightness gain, so the global green compositor remains available.
Alpha is unchanged: this pass replaces light, rather than adding geometry coverage.
**Frost black level**, also in box 12, lifts the darkest frosted areas to a minimum
linear-light value without increasing highlights or altering the green control channel.
The document slider starts at **0.012**; **0** preserves black. Each frame's independent
`frost.blackLevel` is also available in code; the larger of the panel and document floors wins.
Set blur to **0**, brightness to **1**, and both black levels to **0** for an exact no-op.

Captures exclude other frost corrections. Overlapping frosted panels therefore add
independent corrections; they do not provide recursive glass-within-glass rendering.
This is an approximation suited to sparse diagrams, rather than an ordered stack of
transmissive materials. Panels crossing the eye/near plane are hidden, as with Slug.

## Blur-space gamma

Box **12 → frost gamma → Frost blur gamma** changes the averaging space, rather than
applying display gamma after the blur. **1** is the original linear-light Gaussian.
The formula is `blur(C^gamma)^(1/gamma)`, over red/blue light: values above 1 spread
highlights more strongly; values below 1 give a darker, tighter-looking blend. The
range is **0.25–4**. Green control data and alpha always use the original linear blur.

`drawing.frost.blurGamma` controls the renderer from code. The document's root
`frostBlurGamma` drives it before the current frame's captures. The two separable
passes encode before interpolation and decode after filtering; the intermediate
remains linear float16. Gamma 1 takes the original inexpensive texture-sampling path.
Other values require extra fetches at interpolated texels, so their cost can be higher.
This setting is independent of the final compositor's gamma and the frost black level.

## Cost, pooling and reloads

Frost is opt-in on each frame. Drawing's gallery controls enable it for all frames.
A panel needs one full-size and two half-size RGBA16F images per eye, approximately
`24 × eyeWidth × eyeHeight` bytes at scene scale 1. Visible panels share the original
scene meshes and font atlases. Their captures add scene draws and image bandwidth.

`drawing.frost.maximumSurfaces` defaults to **6**, `budgetBytes` to **1 GiB**. The
frame output reports active/cached counts, allocated MiB (including pending GPU retirement) and panels omitted by the
budget. Offscreen/invisible frames need no new capture. Unused slots stay warm for
120 rendered frames, then retire through Field's deferred GPU cleanup queue. Set
`warmFrames = 0` to release them promptly. Shape buffers grow on demand; they do not
allocate once per physics step.

The clipping ABI adds `clipPlane` to the stroke, marker and Slug shader push constants.
Editable shader boxes must retain it and the `backdropDistance` varying/discard when
reloading. Cached word blur uses four reserved push components so it stays within
Vulkan's minimum 128-byte push-constant limit. Frost shaders live in
`src/jvmMain/resources/afield/reading/frost_*.glsl`; rebuild Field after changing them.

The GPU smoke `four.reading.frost` checks the identity setting, signed replacement,
sharp foreground isolation, matching document/panel black floors, unchanged green and alpha, same-frame recording and repeated
allocation/retirement with Vulkan validation enabled.
