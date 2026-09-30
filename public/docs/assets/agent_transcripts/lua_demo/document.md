# Field Document: lua_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 13 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory" "mainLanguage"]}
```

## 1. status — children of this box are Lua

<!-- field-box id="lua_root" index="0" -->

### Narrative

From `code`

This root stays Kotlin; its `mainLanguage` makes the boxes under it Lua.

```edn [field-box id="lua_root" role="properties"]
{"id" "lua_root" "index" 0 "name" "status — children of this box are Lua" "parents" ["root"] "children" ["lua_hello" "lua_thisbox" "lua_drawing" "lua_wave" "lua_spawned" "lua_error" "lua_runaway" "lua_jit" "lua_accumulate" "lua_controls" "lua_sequencer"] "properties" {"children" #{#afield/box "lua_hello" #afield/box "lua_thisbox" #afield/box "lua_drawing" #afield/box "lua_wave" #afield/box "lua_spawned" #afield/box "lua_error" #afield/box "lua_runaway" #afield/box "lua_jit" #afield/box "lua_accumulate" #afield/box "lua_controls" #afield/box "lua_sequencer"} "parents" #{#afield/box "root"} "name" "status — children of this box are Lua" "_tombstone" false "frame" #afield/rect [40.0 40.0 460.0 80.0] "mainLanguage" "lua"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lua_root_code_.kt" "lines" 2 "characters" 118 "sha256" "5bec5fe8a374e800d37268672fb11d4ae7c40b36aa34cc9e76e03a825de20d34"}]}
```

```kotlin [field-property box="lua_root" name="code" path="lua_root_code_.kt"]
// This root stays Kotlin; its `mainLanguage` makes the boxes under it Lua.
println(afield.lua.LuaLibrary.describe())
```

## 2. hello

<!-- field-box id="lua_hello" index="1" -->

```edn [field-box id="lua_hello" role="properties"]
{"id" "lua_hello" "index" 1 "name" "hello" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "hello" "_tombstone" false "frame" #afield/rect [40.0 180.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_hello_code_.kt" "lines" 5 "characters" 145 "sha256" "9def49626f42b2c73702175f9caa13a6d0fc02a7c9e5c1642cb93d3cc1710e96"}]}
```

```lua [field-property box="lua_hello" name="code" path="lua_hello_code_.kt"]
-- Run the box, or select one line and run just that.
local greeting = "hello from " .. field.version
print(greeting)
x = 6 * 7
print("x is", x)
```

## 3. runaway

<!-- field-box id="lua_runaway" index="2" -->

```edn [field-box id="lua_runaway" role="properties"]
{"id" "lua_runaway" "index" 2 "name" "runaway" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "runaway" "_tombstone" false "frame" #afield/rect [230.0 320.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_runaway_code_.kt" "lines" 4 "characters" 141 "sha256" "2f6f1e61435aeed6a45b0ae34fd8175f51a7db6739ed84463bc48d92d1e21ac3"}]}
```

```lua [field-property box="lua_runaway" name="code" path="lua_runaway_code_.kt"]
-- A loop that never ends is stopped after field.budget seconds (5 unless you say).
field.budget = 1
local n = 0
while true do n = n + 1 end
```

## 4. an error

<!-- field-box id="lua_error" index="3" -->

```edn [field-box id="lua_error" role="properties"]
{"id" "lua_error" "index" 3 "name" "an error" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "an error" "_tombstone" false "frame" #afield/rect [800.0 180.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_error_code_.kt" "lines" 4 "characters" 85 "sha256" "32d42349150f745c1823475c91335232f4b5628b6c9c5a5f6a002a6ff76a79fe"}]}
```

```lua [field-property box="lua_error" name="code" path="lua_error_code_.kt"]
local t = {}
print("before the error")
print(t.missing.field)
print("never printed")
```

## 5. jit

<!-- field-box id="lua_jit" index="4" -->

```edn [field-box id="lua_jit" role="properties"]
{"id" "lua_jit" "index" 4 "name" "jit" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "jit" "_tombstone" false "frame" #afield/rect [420.0 180.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_jit_code_.kt" "lines" 7 "characters" 437 "sha256" "3c31aa2db0dce0576f7e982a84ab6a7a3d98ecbeda9c5c12da95da6564448df0"}]}
```

```lua [field-property box="lua_jit" name="code" path="lua_jit_code_.kt"]
-- The JIT starts off so the runaway guard works. Turn it on for code you trust to finish.
local function work(n) local s = 0 for i = 1, n do s = s + i % 7 end return s end
local t0 = os.clock(); work(3e7); local interpreted = os.clock() - t0
jit.on()
t0 = os.clock(); work(3e7); local compiled = os.clock() - t0
jit.off()
print(string.format("interpreted %.3f s, compiled %.3f s: %.0fx", interpreted, compiled, interpreted / compiled))
```

## 6. drawing

<!-- field-box id="lua_drawing" index="5" -->

```edn [field-box id="lua_drawing" role="properties"]
{"id" "lua_drawing" "index" 5 "name" "drawing" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "drawing" "_tombstone" false "frame" #afield/rect [40.0 460.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_drawing_code_.kt" "lines" 14 "characters" 548 "sha256" "cb1ffc3fb63f686b7b44831450c11d30ef4f7dbeeaa6a4b92b3669ff9a077150"}]}
```

```lua [field-property box="lua_drawing" name="code" path="lua_drawing_code_.kt"]
-- FLines from Lua: line() builds one, lines(...) draws them in the box's output.
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

## 7. wave

<!-- field-box id="lua_wave" index="6" -->

```edn [field-box id="lua_wave" role="properties"]
{"id" "lua_wave" "index" 6 "name" "wave" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "wave" "_tombstone" false "frame" #afield/rect [560.0 460.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_wave_code_.kt" "lines" 11 "characters" 337 "sha256" "0d5c6abc6e576fc9f7f22974d4114ab117bddb2494af4677ae053b839ca6d8fa"}]}
```

```lua [field-property box="lua_wave" name="code" path="lua_wave_code_.kt"]
-- Launch this box: update(dt) runs every frame until you stop it.
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

## 8. spawned

<!-- field-box id="lua_spawned" index="7" -->

```edn [field-box id="lua_spawned" role="properties"]
{"id" "lua_spawned" "index" 7 "name" "spawned" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "spawned" "_tombstone" false "frame" #afield/rect [40.0 320.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_spawned_code_.kt" "lines" 8 "characters" 198 "sha256" "29a7ff9d2a5cf9e88857b199308b7386d061a37e4103cd59faa76607a3391993"}]}
```

```lua [field-property box="lua_spawned" name="code" path="lua_spawned_code_.kt"]
-- Launch this box: spawn runs a function as a coroutine, wait(s) pauses it.
spawn(function()
  for i = 1, 4 do
    print("tick", i, "frame", field.frame())
    wait(0.5)
  end
  print("done")
end)
```

## 9. this box

<!-- field-box id="lua_thisbox" index="8" -->

```edn [field-box id="lua_thisbox" role="properties"]
{"id" "lua_thisbox" "index" 8 "name" "this box" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "this box" "_tombstone" false "frame" #afield/rect [230.0 180.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_thisbox_code_.kt" "lines" 5 "characters" 338 "sha256" "1610a02cdc2cf9c29961ada1a1cecd14ce9f830fb4ba175cdc133f106929f0ac"}]}
```

```lua [field-property box="lua_thisbox" name="code" path="lua_thisbox_code_.kt"]
-- box is this box; its properties read and write by name. Run it twice.
box.counter = (box.counter or 0) + 1
print("run", box.counter, "of", tostring(box))
for _, parent in ipairs(box.parents) do print("a parent:", parent.name, "with", #parent.children, "children") end
print("the drawing box is at x =", field.named("drawing").frame.x)
```

## 10. sequencer

<!-- field-box id="lua_sequencer" index="9" -->

```edn [field-box id="lua_sequencer" role="properties"]
{"id" "lua_sequencer" "index" 9 "name" "sequencer" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "sequencer" "_tombstone" false "frame" #afield/rect [40.0 900.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_sequencer_code_.kt" "lines" 9 "characters" 578 "sha256" "7987e3b752f420cdd99be9ab9afe3b0dac3bf6e782e54f018e748f387208a736"}]}
```

```lua [field-property box="lua_sequencer" name="code" path="lua_sequencer_code_.kt"]
-- Lua makes the notes, Field's pattern voice plays them (the norns shape). Run it to hear the
-- sequence loop; run it again for new notes, stop the box for silence.
local scale = {0, 2, 3, 5, 7, 8, 10}
local notes = {}
for i = 1, 8 do notes[i] = 57 + scale[math.random(#scale)] + (i % 4 == 0 and 12 or 0) end
print("notes", table.concat(notes, " "))
local heard = field.render(notes, {cps = 0.5, wave = "triangle", decay = 0.3})
print(string.format("%.0f s, peak %.2f, rms %.3f", heard.seconds, heard.peak, heard.rms))
play(notes, {cps = 0.5, wave = "triangle", decay = 0.3})
```

## 11. accumulates

<!-- field-box id="lua_accumulate" index="10" -->

```edn [field-box id="lua_accumulate" role="properties"]
{"id" "lua_accumulate" "index" 10 "name" "accumulates" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "accumulates" "_tombstone" false "frame" #afield/rect [610.0 180.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_accumulate_code_.kt" "lines" 3 "characters" 146 "sha256" "831c86698b99f7a80786be7a994dc56238fd0b087f720067248f1bc109b943f2"}]}
```

```lua [field-property box="lua_accumulate" name="code" path="lua_accumulate_code_.kt"]
-- One Lua state per box, kept across runs. Select line 2 and run it, then line 3.
function greet(who) return "hello, " .. who end
greet("Field")
```

## 12. controls

<!-- field-box id="lua_controls" index="11" -->

```edn [field-box id="lua_controls" role="properties"]
{"id" "lua_controls" "index" 11 "name" "controls" "parents" ["root" "lua_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "lua_root"} "name" "controls" "_tombstone" false "frame" #afield/rect [990.0 180.0 160.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "lua_controls_code_.kt" "lines" 6 "characters" 323 "sha256" "72625ce0812cadff91b6da8d13cfcffe8a1f4e5d5fa53b70723c887a8c492818"}]}
```

```lua [field-property box="lua_controls" name="code" path="lua_controls_code_.kt"]
-- A slider made from Lua: move it and the circle follows. slider() returns its first value.
local function draw(r)
  local frame = line():rect(0, 0, 300, 200):color(0.5, 0.5, 0.5):width(1)
  lines(frame, line():circle(150, 100, r):color(1, 0.5, 0.2):fill(), {w = 300, h = 200})
end
draw(slider("radius", 5, 90, 40, draw))
```

## 13. line notes

<!-- field-box id="lua_notes" index="12" -->

### Narrative

From `code`

Kotlin: what Field recorded for each line of "hello" when it last ran. Run "hello" first.

```edn [field-box id="lua_notes" role="properties"]
{"id" "lua_notes" "index" 12 "name" "line notes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "line notes" "_tombstone" false "frame" #afield/rect [420.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lua_notes_code_.kt" "lines" 6 "characters" 348 "sha256" "1f6df51aff1dd7906f961eb74bd517c097f3f6d94c3abc9b9db86e64444390d2"}]}
```

```kotlin [field-property box="lua_notes" name="code" path="lua_notes_code_.kt"]
// Kotlin: what Field recorded for each line of "hello" when it last ran. Run "hello" first.
val hello = root.children.first { it.name == "hello" }
val code = hello.getPropertyRaw("code") as String
code.lines().forEachIndexed { i, _ ->
    afield.stdlib.LineNotes.note(hello, "code", code, i + 1)?.let { println("line ${i + 1}: ${it.caption}") }
}
```

