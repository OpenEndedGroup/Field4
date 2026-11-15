# Opening two cellular-automaton examples

The pilot edits two listings in [A cellular automaton you can hear](../../public/docs/chain_ca_sound.md).
The reusable `CaSoundDocs` helpers remain available and linked.

- **Cells to rhythm:** choose the row and column length, extract Boolean masks, and call
  `patCondense` explicitly. The plotted diagrams are the same grids the code samples.
- **Population to pitch:** count each row, check the population formula, take `log2` to obtain
  scale degrees, and add the chosen scale offsets to the MIDI root.

The creative values are at the top. `gasketMask`, `chaosMask`, `populations`, `degrees` and
`notes` are available for inspection. Output setup stays folded; generated preambles are untouched.
The code retains independent checks against binomial parity and the population formula.

## Live verification

The actual visible source was extracted from the two authored fences and executed in Field.
Three variants of each listing ran successively in the same box, exercising declaration rebinding:

| Rhythm: generation / steps / atom | Melody: generations / root / scale |
| --- | --- |
| 20 / 32 / `x` | 32 / 45 / `[0, 3, 5, 7, 10, 12]` |
| 8 / 16 / `x` | 16 / 60 / `[0, 2, 4, 5, 7, 9]` |
| 12 / 24 / `hh` | 8 / 48 / `[0, 7]` |

All six runs passed. The rhythm masks were checked against `gasketBar` and `rule30Bar`;
populations and MIDI notes were checked against `gasketPopulations` and `gasketScale`,
including saturation at the last degree of the short scale.

The two final figures were recaptured through the normal transactional docs runner and inspected.
The published default rhythm has 4 onsets in 21 steps against 18 in 32; the melody has the same
32 notes and MIDI pitches as before. Existing audio uses those same defaults and is unchanged.

Both complete bodies passed through the real editor imports/ktfmt formatter. Editor fold markers
were temporarily carried as comments and restored; raw-string output text was preserved.
