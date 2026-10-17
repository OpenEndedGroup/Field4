# Align graphics prose with typed target and inherited count

`public/docs/afield_four_graphics.md` now publishes the stream target with
`prop<RenderToTexture>(target)`. The prose immediately after that example still says `@exported`
publishes it. Replace that explanation with the typed inherited-property convention.

The compute/draw example now publishes `particleCount` alongside `outputPositions`; its child
reads the same count. The code explains stopping the draw child before a buffer-count rebuild,
then rerunning compute and draw. Both dispatch examples use ceiling division paired with actual
shader bounds. Please reflect these setup gestures in the surrounding explanation where useful.

The stream was exercised through the actual encoder and a private UDP receiver, survived a full
rerun with the same cached sender, and decoded to real 1280x720 video. GPU readbacks cover the
particle count boundary; actual linked 100000-particle captures are saved in batch 11 evidence.
These are private verification artifacts, not regenerated public documentation captures.
