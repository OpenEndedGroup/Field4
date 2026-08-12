# Sonic Pi

*Every public declaration used below is listed, with its KDoc, in the codebase reference: [`SonicPi.kt`](reference/audio.md). Start from [what's already in the box](reference/index.md).*

[Sonic Pi](https://sonic-pi.net) v5 is a live-coding instrument with a genuinely good synth engine, a
sample library, and an idea of time that a text editor can drive. Field talks to it in *owned* mode:
Field starts the Sonic Pi server itself and becomes the client it expects, so there is no Sonic Pi
window — a Field box is the buffer, and Sonic Pi's log, errors and per-line activity come back into
that box.

Owned mode is not a preference. Every interesting message in Sonic Pi's control API is authenticated
with a token the server mints at boot, and v5 prints that token exactly once, to the daemon's stdout.
The long-standing trick of scraping it out of `daemon.log` no longer works: in v5 the boot-time token
log is commented out, and the only surviving one runs inside the daemon's *exit* handler, so it lands
in the log after the daemon has stopped. If you did not start the server, you cannot ask it to run
anything. (A Sonic Pi you launched yourself is still reachable, but only over the unauthenticated cue
port — `SonicPi.cue`.)

## A box that is a piece of music

`#sonicpi` marks a region of Ruby inside an ordinary Kotlin string. `#` is a comment in Ruby, so the
marker is a legal line of the program it introduces — Sonic Pi reads straight past it, and counts it,
which is what keeps its line numbers and the box's line numbers in step.

```diffstatic {data-lang=kotlin}
:::static
sonicPi("""
#sonicpi
live_loop :bikes do
  with_synth :dsaw do
    with_fx :reverb, room: 0.5, mix: 0.3 do
      start_note = chord([:b1, :e2, :b3].choose, :minor).choose
      play start_note, release: 8, cutoff: 90, detune: rrand(0, 0.2)
    end
  end
  sleep 8
end
""")
```

Returning the result as the box's last expression is what ties the music to the box. The box stays lit
for as long as the `live_loop` runs, and stopping the box stops the music — Sonic Pi's job is
cancelled through the same path any other Field launch is.

That mapping matters most for the shape of code above. A `live_loop` never finishes on its own, so
without it the box would flash green for a few milliseconds, go idle, and leave a loop playing with
nothing in the interface admitting responsibility for it.

## Errors arrive late, and land on the line

Sonic Pi reports errors asynchronously, and code inside a `live_loop` fails on its own thread — often
long after the run that started it finished. A mistake there is invisible unless something is still
listening.

Consider a typo that Ruby is happy to parse:

```diffstatic {data-lang=kotlin}
:::static
sonicPi("""
#sonicpi
live_loop :bikes do
  start_note = chord(:e1, :minor).choose
  final_no te = chord(:e2, :minor).choose
  play start_note, note: final_note
  sleep 8
end
""")
```

`final_no te` is not a syntax error. Ruby reads it as `final_no(te = …)`, a call to a method that does
not exist, so nothing complains until the loop runs that line — and then it raises on thread
`:live_loop_bikes`, not on the thread that ran the box.

`Box.sonicPi` wires the box up as the destination before sending the code, so what comes back is put
where it belongs:

```diffstatic {data-lang=text}
:::static
[sonic-pi error in run 2 at line 5] Runtime Error Sonic Pi doesn't know a function called `final_no`

buffer bikes, line 5 - thread :live_loop_bikes
NoMethodError: undefined method 'final_no'
  final_no te = chord(:e2, :minor).choose
```

The line number is marked in the editor as well as printed, and a fresh run clears the previous
marker. Line 5 there is line 5 of the box, not line 5 of some string: the offset between the two is
inferred from where the literal sits in the box's own code, because it is zero only when the
`sonicPi(` call happens to be the first line.

## What is making sound, and where

Sonic Pi tells its client which line just triggered a note. `play`, `synth`, `sample`, `control`,
`play_chord` and the `with_fx` / `live_loop` block boundaries each report the caller's line, so the
editor can show the music moving through the code.

Two things are worth knowing before trusting it. It arrives at musical rate rather than human rate —
two loops at a modest tempo produce something like sixteen reports a second — so the display samples
recency instead of repainting per note, and a line cools off over about half a second rather than
latching on. And it reports *triggering*, not audibility: a `play` at `amp: 0` still creates a node
and still reports, so a lit line does not prove you can hear it.

```diffstatic {data-lang=kotlin}
:::static
val music = sonicPi("""
#sonicpi
live_loop :drums do
  sample :bd_haus, amp: 0.4
  sleep 0.5
end

live_loop :bass do
  use_synth :tb303
  play :e1, release: 0.4, amp: 0.4
  sleep 1
end
""")

// which lines have triggered sound, and how often
music.activity()          // {2=13, 3=13, 5=13, 7=7, 9=7, 11=7}
music.sonicPi.activeLines(withinMs = 500)
```

## The editor knows Sonic Pi's vocabulary

A `#sonicpi` region gets highlighting, hover documentation and completions: functions, the opts of the
function you are calling, and — filtered by context — synth, FX and sample names. `use_synth :` offers
synths; `sample :` offers samples.

None of that vocabulary is written down in Field. Sonic Pi already describes its own language, so
`tools/generate_sonic_pi_vocabulary.py` extracts every signature, opt, synth, FX and sample name from
the installed app:

```diffstatic {data-lang=bash}
:::static
tools/generate_sonic_pi_vocabulary.py
# wrote src/commonMain/resources/sonicpi-vocabulary.json from Sonic Pi 5.0.0:
#   238 fns, 66 synths, 42 fx, 206 samples in 18 groups
```

Several hundred signatures typed out by hand would be wrong somewhere and would rot at the next
release. Extracted, they are correct by construction: v5's new `link_audio` and `set_drive!` appeared
in completions the moment Sonic Pi was installed, without anyone noticing they were new.
`--check` fails when the committed vocabulary no longer matches the installed app.

Here is a region in the editor, with the vocabulary in play:

```diffstatic_runner {data-lang=kotlin capture="ui" region="editor" screenshot="assets/sonic_pi/sonicpi_region.png" wait="0" timeout="120" embed=true hidden=true name="sonicpi-region"}
ui.eval("var s=document.createElement('style');s.textContent='.tick_text.ok{display:none !important}';document.head.appendChild(s);return 'setup';")
ui.openEditor(id)
ui.pause(700)
// Set the model rather than typing it. Typing leaves Monaco's auto-indent and auto-close in the
// picture, and the region only exists once its *closing* triple quote does — a half-typed literal
// has nothing for the extension to highlight, which is exactly what the first attempt captured.
// The triple quote is built from a char code so it does not have to survive three levels of quoting.
ui.eval(
    "var q = String.fromCharCode(34).repeat(3);" +
    "var code = ['sonicPi(' + q, '#sonicpi', 'live_loop :bikes do', '  use_synth :dsaw'," +
    " '  with_fx :reverb, room: 0.5 do', '    play :e2, amp: 0.4, release: 8'," +
    " '    sample :bd_haus', '  end', '  sleep 8', 'end', q + ')'].join('\\n');" +
    "window.globalEditor.getModel().setValue(code); return 'set';"
)
ui.pause(1500)
```

## Ports, tokens and shutting down

The daemon prints one line to stdout and nothing else identifies it: five ports and the token, in the
daemon's own order.

```diffstatic {data-lang=text}
:::static
30201 30198 30197 30199 4560 -1374997887
#     ^      ^     ^     ^    ^
#     |      |     |     |    token — a random *signed* 32-bit int, so often negative
#     |      |     |     osc-cues (4560 when free)
#     |      |     scsynth — still its historical name, though SuperSonic replaced scsynth in v5
#     |      gui-send-to-spider — where /run-code goes
#     gui-listen-to-spider — bound by us, where the log and errors come back
```

Whoever holds the token has to keep sending `/daemon/keep-alive`, or the daemon reaps its whole
process tree. That is a feature: if Field dies, Sonic Pi takes itself down rather than leaving an
orphaned audio server behind. Do not trust the "more frequently than every 3s" in Sonic Pi's own
`daemon.rb` header, though — v5's `KillSwitch` sleeps 40s before it starts watching and then gives up
only after five empty 10s windows, so the real grace period is nearer 90s. Field sends every 1.2s and
asks the daemon to exit outright on close.

One consequence is worth recognising, because it presents as a mystery: a Sonic Pi that was started
and then abandoned holds the cue port for that whole grace period, which quietly pushes the *next*
boot onto a random cue port. If `oscCues` is not 4560, suspect a stranded daemon rather than a
configuration change.

## Notes on getting this wrong

Two failure modes cost real time and are hard to recognise from the symptom.

**A shifted OSC argument reads as a rejected token.** OSC pads every string and typetag to a multiple
of four bytes, adding *nothing* when the length is already aligned. Pad an already-aligned `,is`
typetag by a further four and every argument after it shifts, so `/run-code`'s token arrives as `0`
and Sonic Pi ignores the message — while unauthenticated messages like `/version` keep working
perfectly, which sends you looking in entirely the wrong place. The only evidence is
`Invalid token: 0` in `~/.sonic-pi/log/spider.log`. Field goes through JavaOSC, which gets this right;
the smoke test pins the alignment so a future change of OSC library fails loudly instead of silently.

**Stopping is not instant, and should not look like it.** Sonic Pi's activity reports are queued
against scheduled audio time, so whatever was already scheduled when a stop landed still drains —
about 1.5 seconds in practice. A test that demands silence the instant the box stops is testing the
wrong thing.
