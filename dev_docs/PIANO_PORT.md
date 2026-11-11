# The coopiano port: how it was checked

`src/jvmMain/kotlin/afield/audio/synth/PianoModel.kt` ports
[coopiano](https://github.com/peterc/coopiano) (Peter Cooper, MIT; the notice is kept in the file)
`piano.hpp` and `reverb.hpp`. `Piano.kt` is the Field node around it. This page records how the
port was compared with the C++ and what the numbers were, so the next change can be measured the
same way.

## What coopiano is

coopiano is a modal model, not a waveguide. Each string is up to 600 modes (to 17.5 kHz). The
hammer contact is integrated with symplectic Euler at 32 times the sample rate against every mode
of every unison string, with a hysteretic power-law felt. After separation, each mode becomes two
complex phasors: a vertical polarization that decays fast and a horizontal one that decays at the
fitted late rate. Pairs of neighbouring modes add longitudinal "phantom" phasors at their sum
frequencies. The bridge force then goes through an 88-key sympathetic bank, a stereo soundboard
impulse response synthesised from about 700 modes and equalised to a minimum-phase target, a DC
blocker and an 8-line FDN room.

So `KarplusStrong`/`FractionalDelayLine` in `Physical.kt` is not reused: there is no delay line in
the model to share.

## Same random draws

coopiano draws its unison detuning, polarization detune, strike gains, action noise, bridge
admittance and soundboard modes from `std::mt19937_64` through libc++'s
`uniform_real_distribution` and `normal_distribution`. `PianoRng` reproduces all three:
`generate_canonical<double, 53>` over a 64-bit engine is one draw divided by 2^64, and the normal
is the polar method with a cached second value. `PianoTest.rngReproducesLibcxx` pins the first draws
for seed 42 against values printed by Apple clang 17's libc++; the normal agrees to 1 ulp (Java's
`ln` against Apple libm). With the draws the same, the strike seed formula from coopiano's offline
renderer (`0x9e3779b97f4a7c15 ^ (i * 0x100000001b3) ^ (lround(midi * 64) << 32)`) and the
soundboard seed `0xb0a4d5eed`, a Field render is the same instrument down to rounding.

## The comparison

C++ side, in a scratch directory with coopiano cloned beside it:

```cpp
// bridge.cpp: c++ -O2 -std=c++17 -I../coopiano bridge.cpp -o bridge
#include "piano.hpp"
#include <cstdio>
int main(int argc, char** argv) {
    int midi = atoi(argv[1]); double vel = atof(argv[2]);
    auto key = piano::make_key(midi);
    std::vector<double> buf(8 * piano::FS, 0.0);
    auto d = piano::render_bridge_force(key, vel, buf.size(), buf, 0, 1);
    printf("contact %.3f ms peak %.2f N phasors %d\n", d.contact_ms, d.peak_force, d.n_phasors);
    FILE* f = fopen("bridge.f64", "wb"); fwrite(buf.data(), 8, buf.size(), f); fclose(f);
}
```

and, for the whole chain, `GP_NO_REVERB=1 ./coopiano --preset shared.ini A4 0.5 8`, where
`shared.ini` is `default.ini` with `soundboard.spread` and `soundboard.position_depth` at 0 (the
single shared board the port implements).

Field side: `PIANO_TEST_DUMP=1 ./gradlew jvmTest --tests afield.audio.synth.PianoTest` writes
`build/piano-test/bridge_<midi>.f64` (through `pianoBridgeForce`) and `A4_field.wav`.

### Dry bridge force, seed 1, velocity 0.5, 8 s, 44.1 kHz

| key | contact (C++ / Field) | peak force | phasors | residual energy 0–0.5 s | 2–4 s | 4–6 s |
| --- | --- | --- | --- | --- | --- | --- |
| A4 (69) | 1.973 / 1.973 ms | 9.31 / 9.31 N | 186 / 186 | −91 dB | −76 dB | −70 dB |
| A1 (33) | 3.715 / 3.715 ms | 17.10 / 17.10 N | 1112 / 1112 | peak-relative max 1.4e-4 over 8 s | | |
| C6 (84) | 1.142 / 1.142 ms | 14.94 / 14.94 N | 84 / 84 | peak-relative max 2.6e-5 over 8 s | | |

The residual sits at the level where both sides cull a phasor (−90 dB of the note's peak); the
two cull on different schedules (coopiano bounds each phasor's lifetime, Field compacts every 4096
samples).

### Whole chain, A4 velocity 0.5, shared board, no room, 16-bit WAVs

Waveform residual after a least-squares gain (both files are peak-normalised): −75 dB over
0–0.5 s, −63 dB over 0.5–2 s, −41 dB around 4–7 s, where the level ratio has drifted by 0.2 %.
The last 50 ms differ because coopiano fades its render out. Measured partials and decays
(`.nightly-tmp` analysis script: peak of a 4 s Hann window from 0.5 s, parabolic interpolation;
decays from a heterodyned 50 ms envelope, linear fit in dB):

| partial | coopiano Hz | Field Hz | level rel. n=1 (both) | decay 0.1–1 s (both) | decay 1–7 s (both) |
| --- | --- | --- | --- | --- | --- |
| 1 | 440.037 | 440.037 | 0.0 dB | −17.25 dB/s | −3.43 dB/s |
| 2 | 881.184 | 881.184 | −11.1 dB | −16.84 dB/s | −1.67 dB/s |
| 3 | 1323.536 | 1323.536 | −9.3 dB | −17.58 dB/s | −2.02 dB/s |
| 4 | 1768.277 | 1768.277 | −26.6 dB | −31.59 / −31.60 dB/s | −4.29 / −4.30 dB/s |
| 6 | 2668.117 | 2668.117 | −27.9 dB | −25.41 dB/s | −6.28 / −6.27 dB/s |
| 8 | 3586.849 | 3586.848 | −51.7 dB | | |

Against coopiano's default four-region board (`A4 0.5 8` with no preset) the partial
frequencies still agree to 0.001 Hz and partial levels differ by 0.3 to 1.5 dB, which is the
regions' different modal colouring, not the strings.

## What the port adds

- Inputs at audio rate: `pedal`, `inharmonicity`, `decay`, `coupling`, `soundboard`, `room`,
  `gain`; `hardness` and `bridgeCoupling` are sampled at each strike's sample. To let stiffness move
  while a note rings, every phasor keeps its partial number(s), string fundamental, bridge pull and
  polarization ratio, so its frequency can be recomputed for any inharmonicity scale. Steps are
  recomputed every 32 samples and corrected to second order per sample in between.
- Events (note on/off, CC 64) split the block, and the resonance/board/room chain runs per
  segment, so output is identical at any block size (`PianoTest.outputIsTheSameAtAnyBlockSize`).
  The first version ran the chain after all segments and let a later note open its resonators
  early.

## The four bridge regions

`PianoSoundboardIr.regions` ports `make_soundboard_regions`: the same main draws as the shared
board in the same order, the mode shapes from their own stream (`seed ^ 0x9e3779b97f4a7c15`), the
equaliser derived from the shared response and applied to every region, the heads kept for 0.2 s
with a raised-cosine fade and the shared tail convolved once from the summed force. Routing is
coopiano's `render_force`: each key's own force into its two nearest regions, the rest (sympathetic
strings, `bridgeInput`) shared evenly. `voicing.boardSpread` / `boardPositionDepth` default to
coopiano's 0.7 / 0.5.

Checked in Field only (`PianoRegionsTest`): with spread 0 and depth 1e-12 the region split
reproduces the shared board to 3.7e-9 of a 0.54 peak, so the heads, tail and routing add up; with
the defaults A1 is 6.6 dB louder left and C7 5.6 dB louder right. It has not been compared against
coopiano's C++ with its default four-region preset: building the C++ was not possible in the session
that ported it. To compare, run `./coopiano A4 0.5 8` with `GP_NO_REVERB=1` and no preset against
`pianoRender(8.0, Piano(sampleRate = 44_100, room = Param(0f))) { noteOn(69, 0.5, 0) }`, as in the
whole-chain section above.

## What it leaves out
- Voice stealing with a fade, the live MIDI host, presets from `.ini`, and coopiano's prepared-strike
  cache. Instead of a cache, strikes are prepared on `PianoStrikePool` workers up to a second before
  their sample, with the hammer, bridge and soft-pedal values the audio thread last read. At the
  strike the audio thread takes the worker's result if those values still hold, and prepares it
  itself otherwise, so the output is identical either way
  (`PianoPreparationTest.strikesPreparedAheadAreTheSame`). The strike's random seed is fixed when
  the note is queued, not when it sounds. Live, a note played "now" waits up to 30 ms for its
  worker rather than stalling the block; `pianoRender` always waits.

## What the port adds beyond coopiano

- Exact point-mass modes (`PianoMassCoupling`): the heaviest mass on each string solves
  `K v = λ(I + c s sᵀ) v` over the lowest 48 partials by the secular equation (bracketed Newton, one
  root per interval, `PianoCouplingTest` against a dense Jacobi solve to 1e-14), carries the struck
  state into the prepared modes (`η = VᵀMz`) when the bolt is there at the strike and adiabatically
  afterwards, and remixes bridge, microphone and rattle weights. 8 g at 0.3 on A4: 155, 645, 1272,
  1506 Hz exact against 184, 320, 1017, 944 by the per-mode rule. The sympathetic strings
  (`PianoResonance.prepare`) take the same solve over their 16 partials, retuned every 256 samples.
- Preparations (`PianoPreparation`): a point mass `m` at `x` scales partial `n` by
  `1/sqrt(1 + 2(m/M)·sin²(nπx))` (first-order point-mass perturbation, written so it never reaches
  zero; `M` is the string's mass), and damping adds `c·sin²(nπx)` to its decay rate, floored at
  zero. Both are applied per phasor in the 32-sample recompute, to each parent of a longitudinal
  phantom separately. A 2 g bolt at 0.3 on A4 measures 297, 542, 1222 Hz against the formula's 297,
  542, 1219. The rattle is a coasting object with friction (60/s) and restitution 0.3 inside a gap,
  ringing a 2-pole resonator at each catch with the relative speed. Modes are not coupled.
- Close microphones (`PianoMic`), each with position, balance pan and gain, optionally one string;
  they also hear the sympathetic strings' force.
- `bridgeInput` (25 N per unit) into the resonance and board; `unaCorda` (contact share of the last
  string, felt × (1 − 0.3u)); `sostenuto` latched every 64 samples; CC 66/67 from MIDI clips.

## Cost

Offline on an M-series laptop: 8 s of A4 renders in about 0.9 s through the whole chain, after
the soundboard response is built once per sample rate and voicing (cached).
