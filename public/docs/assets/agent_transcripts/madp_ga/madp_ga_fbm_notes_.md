# fbm bands × noise hues

`x y + 7 * 2.2 fbm 0.65 0.55 hsl` runs layered noise along the diagonal and uses it as a hue, so
the bands are rough rather than smooth. `x 6 * perlin y 9 * perlin + 0.5 * 0.7 0.55 hsl` adds a
noise in `x` to a noise in `y`.

Both end in `... 0.55 hsl`, so their children keep a bright, even lightness and mostly change
how the hue moves. Watch the numbers in front of `fbm` and `perlin`: they set how busy the
picture is.
