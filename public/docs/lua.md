# Lua in a Box

[Lua](https://www.lua.org) is the scripting language inside monome norns, Renoise, Reaper, LÖVE and
Defold. Field runs it through [LuaJIT](https://luajit.org), Mike Pall's implementation of Lua 5.1,
loaded straight into Field's own process. A Lua box can print, show HTML in its output, read and
write its own properties and other boxes', and draw lines, and a launched Lua box gets an
`update(dt)` every frame, the way a LÖVE game or a norns script does.

## Installing LuaJIT

Field does not ship LuaJIT. On a Mac:

```diffstatic {data-lang=bash}
brew install luajit
```

On Debian or Ubuntu, `apt install libluajit-5.1-2`. Field looks in Homebrew's and the usual Linux
library directories; `AFIELD_LUA_LIBRARY` (or `-Dafield.lua.library`) points it anywhere else. Until
it is installed, a Lua box says so and says how to install it, and the rest of Field does not notice.

## Making a Lua box

Set a box's Main Language to **Lua** (the command palette's *Set Main Language: Lua*) and the boxes
connected under it are Lua boxes. The root itself stays Kotlin. Run a Lua box the way you run any
other box: the whole box, or a selection. A selection that is a single expression is shown with its
value, so selecting `x * 2` and running it answers `84`.

```diffstatic_runner {data-lang=lua screenshot="assets/lua/hello.png" capture="editor" outputs="inline" requires="luajit" wait="3" padding="10" timeout="180" embed=true name="lua-hello" w="900" h="220" alt="A four-line Lua box in Field's editor: local greeting = 'hello from ' .. field.version, print(greeting), x = 6 * 7, print('x is', x). Below the code, the box's output reads 'hello from LuaJIT 2.1' followed by a version number, and then 'x is' and 42 separated by a tab."}
local greeting = "hello from " .. field.version
print(greeting)
x = 6 * 7
print("x is", x)
```

Each Lua box has a Lua of its own, made the first time it runs and kept until the box is deleted or
stops being a Lua box. Globals survive from one run to the next, so a box can define a function in
one run and call it from a selected line in the next. Two Lua boxes never share globals.

## What a Lua box can reach

`box` is the box the code is in. Its properties are read and written by name, a missing one is read
from the nearest parent that has it, the way Field's inherited properties are, and `box.children`
and `box.parents` are lists of boxes. `field.named(name)` finds another box in the document.

```diffstatic_runner {data-lang=lua screenshot="assets/lua/box.png" capture="editor" outputs="inline" requires="luajit" wait="3" padding="10" timeout="180" embed=true name="lua-box" w="900" h="240" alt="A Lua box that increments box.counter, prints 'this is box' followed by the box's name and 'run 1', then prints its frame's width and one line per parent box."}
box.counter = (box.counter or 0) + 1
print("this is", tostring(box), "run", box.counter)
print("my frame is", box.frame.w, "wide")
for _, parent in ipairs(box.parents) do print("a parent:", parent.name) end
```

Values cross as plain data: Lua numbers, strings and booleans become Kotlin `Double`, `String` and
`Boolean`, a table with keys `1..n` becomes a list, any other table a map, and `box.frame` is a table
with `x`, `y`, `w` and `h`. Assigning `nil` removes a property. `children`, `parents` and `id` are
read-only.

`print` writes to the box's output, with its arguments separated by tabs as Lua's own `print` does,
and `display(channel, html)` puts HTML into one of the box's output panels.

## Drawing

`line()` starts a line and its methods add to it: `moveTo`, `lineTo`, `cubicTo`, `close`, `rect`,
`circle`, and `color(r, g, b, a)`, `width(w)`, `fill()` for its style. Each returns the line, so they
chain. `line{{x, y}, …}` is a polyline through those points. `lines(...)` draws any number of them in
the box's `lines` panel, and a table at the end sets its size.

```diffstatic_runner {data-lang=lua screenshot="assets/lua/drawing.png" capture="box" open-outputs=true trim=true requires="luajit" wait="3" wait-output=true wait-output-kind="display" timeout="180" embed=true name="lua-drawing" w="820" h="80" alt="The lines panel of a Lua box: twenty-four spokes from a common centre, alternately long and short, shading from blue on the right through violet to pink as they turn, under a filled yellow circle at the centre, inside a thin grey rectangle."}
local shapes = {}
for k = 0, 23 do
  local a = k * math.pi / 12
  local r = (k % 2 == 0) and 130 or 80
  shapes[#shapes + 1] = line()
    :moveTo(200, 150)
    :lineTo(200 + r * math.cos(a), 150 + r * math.sin(a))
    :color(k / 23, 0.45, 1 - k / 23)
    :width(3)
end
shapes[#shapes + 1] = line():circle(200, 150, 42):color(1, 0.8, 0.2, 0.9):fill()
shapes[#shapes + 1] = line():rect(20, 20, 360, 260):color(0.6, 0.6, 0.6):width(1)
lines(shapes, {w = 400, h = 300})
```

As in all of Field's 2D output, y grows downwards.

## Every frame: `update`, `spawn` and `wait`

**Launch** a Lua box, rather than running it, and after the chunk has run Field calls its global
`update(dt)` once a frame, with the seconds since the last one, until the box is stopped. This draws a
travelling wave:

```diffstatic_runner {data-lang=lua screenshot="assets/lua/wave.png" execution="launch" capture="box" open-outputs=true trim=true requires="luajit" wait="3" wait-output=true wait-output-kind="display" timeout="180" embed=true name="lua-wave" w="820" h="80" alt="The lines panel of a launched Lua box: a light-blue sine wave, two full cycles across the panel, caught at one moment of its travel."}
local t = 0
function update(dt)
  t = t + dt
  local l = line()
  for i = 0, 100 do
    local x, y = i * 4, 100 + 60 * math.sin(i / 8 + t * 3)
    if i == 0 then l:moveTo(x, y) else l:lineTo(x, y) end
  end
  lines(l:color(0.3, 0.8, 1):width(2), {w = 400, h = 200})
end
```

`spawn(fn)` runs a function as a coroutine alongside `update`, resumed once a frame, and `wait(s)`
inside it pauses it for that many seconds, or a single frame when `s` is left out. A launched box with
no `update` stops by itself when its last spawned function returns.

```diffstatic_runner {data-lang=lua screenshot="assets/lua/spawn.png" execution="launch" capture="editor" outputs="inline" requires="luajit" wait="4" padding="10" timeout="180" embed=true name="lua-spawn" w="900" h="300" alt="A Lua box that spawns a function printing tick 1 to tick 4 with half a second between them, then done. Its output lists the four ticks, each with the Field frame it happened on, and then done."}
spawn(function()
  for i = 1, 4 do
    print("tick", i, "frame", field.frame())
    wait(0.5)
  end
  print("done")
end)
```

Running (rather than launching) a box that defines `update` or spawns a function says it needs a
launch. Stopping the box ends `update` and drops its spawned functions, but its globals stay.

## Sliders

`slider(label, min, max, initial, fn)` puts a slider in the box's `controls` panel and calls `fn`
with its value each time it moves; it returns `initial`, so the first drawing and every later one can
go through the same function. `checkbox(label, initial, fn)` is the same for a checkbox. The callback
runs in the box's Lua, on Field's main thread, under the frame budget.

```diffstatic_runner {data-lang=lua screenshot="assets/lua/slider.png" capture="box" open-outputs=true trim=true requires="luajit" wait="3" wait-output=true wait-output-kind="display" timeout="180" embed=true name="lua-slider" w="820" h="80" alt="A Lua box's output: a controls panel with one slider, RADIUS, at 40, and below it a lines panel with a filled orange circle centred in a thin grey rectangle."}
local function draw(r)
  local frame = line():rect(0, 0, 300, 200):color(0.5, 0.5, 0.5):width(1)
  lines(frame, line():circle(150, 100, r):color(1, 0.5, 0.2):fill(), {w = 300, h = 200})
end
draw(slider("radius", 5, 90, 40, draw))
```

## Errors

An error is marked on the line it happened on, after whatever the run printed first:

```diffstatic_runner {data-lang=lua screenshot="assets/lua/error.png" capture="editor" outputs="inline" expect-error=true requires="luajit" wait="3" padding="10" timeout="180" embed=true name="lua-error" w="900" h="260" alt="A four-line Lua box. Under line 3, print(t.missing.field), a red band reads: attempt to index field 'missing' (a nil value). The box's output below holds the one line printed before it: before the error."}
local t = {}
print("before the error")
print(t.missing.field)
print("never printed")
```

A run that is still going after `field.budget` seconds (5 unless the box sets it) is stopped with an
error on the line it had reached, and so is one frame of `update` that takes longer than
`field.frameBudget` (1 second). Box code runs on Field's main thread, so without this a loop that
never ends would freeze Field until it was killed.

```diffstatic_runner {data-lang=lua screenshot="assets/lua/runaway.png" capture="editor" outputs="inline" expect-error=true requires="luajit" wait="4" padding="10" timeout="180" embed=true name="lua-runaway" w="900" h="200" alt="A three-line Lua box that sets field.budget = 1 and then loops forever. Under line 3, while true do n = n + 1 end, a red band reads: ran for more than 1 s without finishing, so it was stopped (a loop that never ends?)."}
field.budget = 1
local n = 0
while true do n = n + 1 end
```

## The JIT starts off

LuaJIT's speed comes from compiling hot loops to machine code, and compiled code never calls the hook
that notices a run has gone on too long. So in a Lua box the compiler starts **off**, and the
interpreter, which LuaJIT also makes fast, runs everything. `jit.on()` turns the compiler on for that
box's Lua, for code you trust to finish: a loop that never ends, once compiled, cannot be stopped.

```diffstatic_runner {data-lang=lua screenshot="assets/lua/jit.png" capture="editor" outputs="inline" requires="luajit" wait="4" padding="10" timeout="180" embed=true name="lua-jit" w="900" h="220" alt="A Lua box that times the same thirty-million-step loop twice, once with the JIT off and once after jit.on(). Its output gives both times in seconds and how many times faster the compiled loop was."}
local function work(n) local s = 0 for i = 1, n do s = s + i % 7 end return s end
local t0 = os.clock(); work(3e7); local interpreted = os.clock() - t0
jit.on()
t0 = os.clock(); work(3e7); local compiled = os.clock() - t0
jit.off()
print(string.format("interpreted %.3f s, compiled %.3f s: %.0fx", interpreted, compiled, interpreted / compiled))
```

## Line notes

Rest the cursor on a line of a Lua box that has run and its [line note](language_extensions.md#line-notes)
says what the line came to on the last run: the value of each name it assigned (`x = 42`), how many
times it ran when that was more than once, `defined twice` for a function, where a failed run stopped,
and which lines it never reached. Recording costs something for each line the top level runs, up to a
limit, after which the run goes on unwatched. `AFIELD_LUA_LINE_NOTES=0` in Field's environment turns
it off.

## Completion

Completion offers Lua's keywords and the globals the box's Lua has defined, the members of a table
after `name.` (so `string.` lists the string library and `field.` Field's own), a line's methods, and
after `box.` the box's properties.

## The demo scene

`dev_docs/lua/lua_demo.yaml` builds all of this in one document: a Kotlin root, ten Lua boxes, and a
Kotlin box that reads the Lua boxes' line notes. Replay it with the backdoor:
`macro write lua_demo --file dev_docs/lua/lua_demo.yaml`, then `macro run lua_demo`.

??? aside "How this is built, and what it leaves out"
    The binding is Java's foreign-function API against the Lua 5.1 C API LuaJIT exports, with no
    wrapper library of Field's own; the mechanics, the measurements and the traps are in
    `dev_docs/lua/LUA.md`. Two limits: LuaJIT's own `ffi` library is present but nothing here is
    built on it, and Lua cannot yet reach Field's audio or pattern layers — only what the prelude in
    `src/jvmMain/resources/afield/lua/prelude.lua` defines.
