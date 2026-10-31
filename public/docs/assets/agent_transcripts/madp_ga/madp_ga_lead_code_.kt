/*
GA: interactive evolution, after Karl Sims. Each tile is a little program run once per pixel,
and you are the fitness function: click two tiles and they become the parents of the next
generation. A child copies one parent, sometimes borrows a few words from the other, and
mutates. Reroll breeds again from the same parents; Fresh Parents starts from noise.

The language is reverse Polish: every word takes its inputs off a stack.
  x y          position, 0..1 (y grows downward)
  r a          distance from the centre, angle around it (both 0..1)
  rgb hsl      three numbers -> a colour
  sat hue lum  set one part of a colour
  + - * /      numbers, or colours channel by channel (wrapped into 0..1)
  sin cos tan asin acos atan exp ln invert normalize   change one value
  perlin       phase -> noise      fbm   phase lacunarity -> layered noise
  dot          two values -> a number
  a b c if     b when c > 0.5, else a
  blend        a number and two colours -> the colours mixed
  dup          copy the top
An empty stack reads 0.5 and unknown words are skipped, so every program draws something.

These two parents are the demo's starters. Edit them and run the box again (Alt+Enter); the
parents you pick are printed under the grid, ready to paste back in here.
*/
val ga = colorRpnEvolution(
    """
    x 9 * sin 0.18 +
    y 0.55 +
    0.62
    hsl
    """,
    """
    a 14 * cos 0.14 +
    r 0.78 * 0.12 +
    0.48
    hsl
    """,
    ColorRpnBreeding(mutationRate = 0.12, targetLength = 18),
)
