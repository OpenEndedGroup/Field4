# DaVinci Resolve: Timelines and Markers

Read the project you have open in DaVinci Resolve from a Field box: its timelines, their markers,
markers on clips and the playhead position. Each marker comes back with its frame on the timeline,
so you can line up an animation, a score or a lighting cue with your edit.

Box code can use `Resolve` without an import. This box lists the markers on the open timeline:

```diffstatic {data-lang=kotlin}
for (m in Resolve.markers()) print("${m.timecode}  frame ${m.frame}  ${m.color}  ${m.name}: ${m.note}")
```

!!! note "Setting up Resolve"
    Resolve has to be running with a project open, and it has to accept scripts from other
    programs: Preferences > System > General > External scripting using > **Local**. Field uses the
    scripting module that comes with Resolve, so there is nothing else to
    install. When Resolve cannot be reached, each call fails with a `ResolveUnavailable` that says
    which of these is missing.

## Markers

A `ResolveMarker` holds:

| field | what it is |
| --- | --- |
| `frame` | frames from the first frame of the timeline |
| `recordFrame`, `timecode` | the same position on the timeline's own clock |
| `seconds` | `frame / fps` |
| `duration` | length in frames, 1 for a single-frame marker |
| `color` | `Blue`, `Cyan`, `Green`, `Yellow`, `Red`, `Pink`, `Purple`, `Fuchsia`, `Rose`, `Lavender`, `Sky`, `Mint`, `Lemon`, `Sand`, `Cocoa` or `Cream` |
| `name`, `note` | the name and the note from Resolve's marker dialog |
| `customData` | text a script attached to the marker, which Resolve's UI never shows |

`frame` is the number to lay things out with: frame 0 is the start of the timeline whatever timecode
the timeline starts at, so a timeline that starts at `01:00:00:00` at 24 fps puts frame 0 at
`recordFrame` 86400. Timecodes on a 29.97 or 59.94 drop-frame timeline use `;` before the frames.

The drawings made with the viewer's annotation tool are not available to scripts, so a marker's
annotation here is its text: the name, the note and the custom data.

`Resolve.markers("Edit 2")` reads the timeline called "Edit 2". If the project has no timeline
by that name, the error lists the available names. `Resolve.timeline(name)` returns the
whole `ResolveTimeline`: its name, `fps`, `dropFrame`, `startFrame`, `endFrame`, `length`,
`startTimecode`, whether it is the `current` one, and its `markers`. Two helpers pick markers out:
`markersAt(frame)` returns those whose duration covers a frame, and `markersColored("Red")` those
of one color.

This box puts the red markers of a timeline called "Edit 2" in a table:

```diffstatic {data-lang=kotlin}
val edit = Resolve.timeline("Edit 2")
val rows =
    edit.markersColored("Red").joinToString("\n") { "| ${it.timecode} | ${it.frame} | ${it.name} | ${it.note} |" }
markdown("${edit.name} at ${edit.fps} fps\n\n| timecode | frame | name | note |\n| --- | --- | --- | --- |\n$rows")
```

`Resolve.timelines()` returns every timeline in the project, in the order Resolve lists them, each
with its markers.

## Markers on clips

Resolve also keeps markers on the clips in a timeline, separate from the timeline's own.
`Resolve.clipMarkers()` (or `clipMarkers("Edit 2")`) returns those from every video and audio track,
placed on the timeline: `frame`, `recordFrame` and `timecode` say where the marker shows in the
edit, and `clip`, `trackType` (`"video"` or `"audio"`), `track` and `clipFrame`, the frame in the
clip's source media, say where it came from. A clip marker on a frame the edit has trimmed off is
left out.

## The playhead

`Resolve.playhead()` says where the playhead of the current timeline is, as a `timecode`, a
`recordFrame` and a `frame` counted from the timeline start, so it compares directly with a
marker's `frame`. Resolve reports a playhead only for the timeline open in its viewer. This box
names the marker under the playhead:

```diffstatic {data-lang=kotlin}
val here = Resolve.playhead()
val under = Resolve.timeline().markersAt(here.frame)
print("${here.timecode}: ${under.joinToString { it.name }.ifEmpty { "no marker" }}")
```

Each call asks Resolve again, so it sees the current state of the project.

You can also move the playhead. Frames and seconds count from the timeline start; a timecode
includes the timeline's own record-clock offset:

```diffstatic {data-lang=kotlin}
Resolve.setPlayhead(240) // frame 240 from the timeline start
Resolve.setPlayheadSeconds(10.0) // the same position on a 24 fps timeline
Resolve.setPlayhead("01:00:10:00") // absolute record timecode
```

Each setter returns the observed `ResolvePlayhead`. Seconds round to the nearest timeline frame;
negative, nonfinite and out-of-range positions are rejected. Drop-frame conversion follows the
timeline's settings. An optional timeline name guards against moving the wrong open edit:
[`Resolve.on("macbook-pro-2").setPlayheadSeconds(10.0, "Marked for real")`](reference/networking.md#resolve-kt--resolve--on). That timeline must
already be current; Field never switches Resolve's timeline automatically. The corresponding
guarded getter is `other.playhead("Marked for real")`.

### Following a Field movie without blocking playback

[`ResolvePlayheadLink`](reference/networking.md#resolveplayheadlink-kt-resolveplayheadlink) owns a single background worker. [`offer(seconds)`](reference/networking.md#resolveplayheadlink-kt--resolveplayheadlink--offer) only replaces the latest
target; it never queues a network operation or waits for Resolve. Following runs at most four
times a second by default, retries failures with a backoff, and checks paused positions once a
second so a delayed or manually moved playhead can converge again.

```diffstatic {data-lang=kotlin}
val link = ResolvePlayheadLink(Resolve.on("macbook-pro-2"), "Marked for real")
link.enabled = true
loop {
    link.offer(movie.timestamp) // uploaded source-video seconds; do not multiply by playback speed
    frame()
}
    .end { link.close() }
```

Offer `null` while no movie frame is available. `link.status` reports the latest target and
observed position, whether a call is busy, whether the target is aligned within half a frame,
completed read/write counts and the last error. Failures and recoveries are logged under
`resolve.playhead`. A modal dialog or disconnected machine can delay convergence, but playback
does not wait and obsolete targets do not accumulate.

For the opposite direction, `link.getPlayhead()` immediately disables following and returns a
`CompletableFuture<ResolvePlayhead>`. Check `isDone` from Field's loop before reading the result,
then call the movie's ordinary asynchronous `seek(position.seconds)`. This pauses playback at
the chosen image. Re-enable following when ready. A failed Get completes exceptionally so the
interface can report it and offer another attempt. Closing cancels pending Gets and prevents new
writes without blocking the caller; an already-running Resolve call is allowed to finish.

## Resolve on another machine

Use `Resolve.on("macbook-pro-2")` to reach Resolve on another machine, by host name or address.
A Tailscale name works too. It has the same calls as `Resolve`:

```diffstatic {data-lang=kotlin}
val other = Resolve.on("macbook-pro-2")
for (m in other.markers()) print("${m.timecode} ${m.color} ${m.name}")
```

That machine must be running DaVinci Resolve Studio with Preferences > System > General > External
scripting using set to **Network**. Resolve listens on port 1144 and answers on a port between 49152
and 65535, so a firewall between the two machines has to let both through. When nothing answers on
port 1144 within 5 seconds, the call fails with a `ResolveUnavailable` saying so.

## From a Python box

The same functions are in the `fieldresolve` Python package, which Field puts on the path of Python
boxes. They return dicts with the same keys:

```diffstatic {data-lang=python}
import fieldresolve as fr

for m in fr.markers("Edit 2"):
    print(m["timecode"], m["color"], m["name"], m["note"])
```

`fr.timelines()`, `fr.timeline(name)`, `fr.clip_markers(name)`, `fr.playhead()` and `fr.status()`
match the Kotlin calls, and each takes `host="macbook-pro-2"` for another machine. From a terminal
in Field's `python/` directory, `python -m fieldresolve markers "Edit 2"` prints the markers as JSON.
Use `fr.set_playhead(240)`, `fr.set_playhead(seconds=10.0)` or
`fr.set_playhead(timecode="01:00:10:00")` to move the current timeline, with optional `name` and
`host` arguments. These return the same playhead dict as `fr.playhead()`.

## How it connects

Resolve's scripting module is a Python extension, so the Kotlin calls go through a small Python
program, `python -m fieldresolve`, which Field starts on the first call and keeps running. It runs
on `AFIELD_RESOLVE_PYTHON` if that is set, otherwise on the checkout's `.venv`, otherwise on the
first `python3` it finds; any Python 3.6 or later works. A call that Resolve does not answer within
`Resolve.timeoutSeconds` (15 s; a modal dialog open in Resolve holds scripts back) stops that
program, and the next call starts another. Its error output goes to `Resolve.logFile()`. For a
Resolve installed somewhere other than the default place, set `RESOLVE_SCRIPT_API` and
`RESOLVE_SCRIPT_LIB` as Resolve's scripting README describes.

## API reference

Implementation and parameter details for the helpers used above:

- [`ResolvePlayheadLink.close`](reference/networking.md#resolveplayheadlink-kt--resolveplayheadlink--close)
