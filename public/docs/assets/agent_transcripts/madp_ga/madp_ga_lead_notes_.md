# GA

Interactive evolution, the way Karl Sims did it in *Genetic Images* (1991): **the code is the
genome, and your eye is the fitness function.** Every tile is a little program, run once for each
pixel. Nothing scores the pictures. You look at nine of them, click the two you like, and those two
become the parents of the next nine.

This is the madp **GA** demo (`ga.html`) in Field. The programs, the breeding rules and the look of
the grid are the demo's; a program pasted from one draws the same picture in the other.

## Breeding

Run this box. Its output panel shows a 3×3 grid: **parent a** and **parent b** (blue outlines), then
seven children.

- **Click two tiles** and they become the new parents; seven new children appear. The first click
  marks a tile in amber; click it again to take it back.
- **Reroll** breeds seven new children from the same parents.
- **Fresh Parents** throws both parents away for two random programs.
- **Start over** goes back to the two parents written in the code.
- **mut**, **len** and **res** are the mutation rate, the length a child is nudged toward, and the
  resolution each tile renders at. Changing one breeds again.

Each child copies one parent at random. About four times in ten it first borrows one to three words
from the other parent (`seed` in its header). Then it takes a few mutations, named in its header:
`num` nudges a number, `var` turns a number into `x`, `y`, `r` or `a`, `op` swaps a word,
`splice` borrows from the other parent again, `copy` repeats a stretch of itself, `drop` deletes,
`add` inserts and `swap` exchanges two words. Last, its length is topped up or trimmed toward
**len**, give or take two.

The parents you have picked are printed in full under the grid. To keep one, copy it into the code
above and run the box again; editing a parent in the code and re-running (alt-R) is how you steer.

## The language

Reverse Polish: each word takes its inputs off a stack and pushes its result. Values are numbers or
colours.

| words | what they do |
| --- | --- |
| `x` `y` | position, 0 to 1 across the tile (`y` grows downward) |
| `r` `a` | distance from the centre (1 at a corner), angle around it (0 to 1) |
| `rgb` `hsl` | three numbers → a colour |
| `sat` `hue` `lum` | a colour and a number → the colour with that part set |
| `+ - * /` | numbers, or colours channel by channel, wrapped into 0..1; `-` on colours is the difference; `/` never divides by zero |
| `sin cos tan asin acos atan exp ln invert normalize` | change one value; `invert` is `1 - v` |
| `perlin` | phase → smooth noise; `fbm` phase lacunarity → four octaves of it |
| `dot` | two values → a number |
| `a b c if` | `b` when `c` > 0.5, otherwise `a` |
| `blend` | a number and two colours → the colours mixed |
| `dup` | copy the top |

Nothing fails. An empty stack reads 0.5, unknown words are skipped, and whatever is left becomes a
colour: a colour on top is the answer; two colours average; a colour and a number mix 70/30; two
numbers make a blue-to-orange colour; one number is a grey. So every program draws something, and
mutation never has to be careful.

`hsl` is the quickest route to something good: `x 9 * sin 0.18 +  y 0.55 +  0.62  hsl` puts a
wave in the hue and a ramp in the saturation.

## How this maps to Field

| ga.html | here |
| --- | --- |
| the two Monaco editors | the two strings in this box |
| the 3×3 grid, clicking two tiles | `colorRpnEvolution(...)`, a grid in the box's `ga` output panel |
| toolbar mut / len / res, Reroll, Fresh Parents | the same, in the grid's toolbar; `ColorRpnBreeding` sets the starting values |
| the breeding rules | `ColorRpnBreeding`: rate, length, splice chance, the mutation mix (see the last box) |
| the interpreter | `ColorRpn.compile(source)`; `program.png(256)` draws one |

The grid itself is `evolveByEye(...)`, which takes any genome with a picture and a way to breed it,
so the same click-to-breed loop works for StackSL programs, FLines or anything else you can draw.

**Why the demo's language and not StackSL.** StackSL is Field's own GPU stack language and has an
evolve layer of its own, but the point here is reading the genome: a tile's caption is a program
you can follow, change and paste back. Keeping the demo's language keeps its programs, its
starters and its examples working unchanged. It runs on the CPU: nine tiles at 256 pixels are well
under a second, and no window or GPU is needed. (Compiling it to a shader was not worth it: `if`
can choose a number on one pixel and a colour on the next, so the stack's types change per pixel.)

## What differs

- Picking a tile does not rewrite the code; the parents are printed under the grid for you to copy.
- Tiles appear when the whole generation is drawn, without the demo's per-tile progress bars.
- The demo's AI completion, "Copy LLM intro" and local saves are not here: Field has its own
  completion and the AI4 chat, and the document keeps its own history.
