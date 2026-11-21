# Field Document: norns_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole" "mainLanguage"]}
```

## 1. status — children of this box are Lua

<!-- field-box id="norns_root" index="0" -->

### Narrative

From `code`

This root stays Kotlin; its `mainLanguage` makes the boxes under it Lua.

```edn [field-box id="norns_root" role="properties"]
{"id" "norns_root" "index" 0 "name" "status — children of this box are Lua" "parents" ["root"] "children" ["norns_orbits" "norns_orbits_render" "norns_fetch" "norns_noiz" "norns_noiz_render" "norns_anima_render"] "properties" {"children" #{#afield/box "norns_orbits" #afield/box "norns_orbits_render" #afield/box "norns_fetch" #afield/box "norns_noiz" #afield/box "norns_noiz_render" #afield/box "norns_anima_render"} "parents" #{#afield/box "root"} "name" "status — children of this box are Lua" "_tombstone" false "frame" #afield/rect [40.0 40.0 460.0 80.0] "mainLanguage" "lua"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "norns_root_code_.kt" "lines" 2 "characters" 123 "sha256" "0c5c3b05d0033b63699db8214037fa6effb0f36d7367653fff0b359394b927ab"}]}
```

```kotlin [field-property box="norns_root" name="code" path="norns_root_code_.kt"]
// This root stays Kotlin; its `mainLanguage` makes the boxes under it Lua.
println(afield.lang.lua.LuaLibrary.describe())
```

## 2. orbits, rendered

<!-- field-box id="norns_orbits_render" index="1" -->

```edn [field-box id="norns_orbits_render" role="properties"]
{"id" "norns_orbits_render" "index" 1 "name" "orbits, rendered" "parents" ["root" "norns_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "norns_root"} "name" "orbits, rendered" "_tombstone" false "frame" #afield/rect [280.0 180.0 200.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "norns_orbits_render_code_.kt" "lines" 11 "characters" 738 "sha256" "5c95f1aa006122129d9ccba2a75dc87b921cbf95be6544e6ebdb5f097061d066"}]}
```

```lua [field-property box="norns_orbits_render" name="code" path="norns_orbits_render_code_.kt"]
-- Run (not launch): the "orbits" box's script, offline, in virtual time, for eight seconds.
-- E2 speeds it up at 2 s, K3 rerolls the orbits at 4 s, E3 closes the filter at 5 s.
local norns = require "norns"
local heard = norns.render {
  code = field.named("orbits").code, name = "orbits", seconds = 8,
  events = { { 2, "enc", 2, 30 }, { 4, "key", 3, 1 }, { 4.1, "key", 3, 0 }, { 5, "enc", 3, -60 } },
  wav = os.getenv("HOME") .. "/.afield/norns/renders/orbits.wav",
  png = os.getenv("HOME") .. "/.afield/norns/renders/orbits.png",
}
print(string.format("%.1f s, peak %.2f, rms %.3f, %d onsets", heard.seconds, heard.peak, heard.rms, heard.onsets))
print("tempo now", params:get("tempo"), "cutoff", math.floor(params:get("cutoff")))
```

## 3. orbits

<!-- field-box id="norns_orbits" index="2" -->

```edn [field-box id="norns_orbits" role="properties"]
{"id" "norns_orbits" "index" 2 "name" "orbits" "parents" ["root" "norns_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "norns_root"} "name" "orbits" "_tombstone" false "frame" #afield/rect [40.0 180.0 200.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "norns_orbits_code_.kt" "lines" 79 "characters" 2541 "sha256" "08d48062e7dc32d68d0cca3d4bbc58ac760dea29e0afdc6a2b8e77e174c326f5"}]}
```

```lua [field-property box="norns_orbits" name="code" path="norns_orbits_code_.kt"]
-- orbits: a norns script, written in this box
-- E2 tempo, E3 brightness (filter cutoff)
-- K2 play / stop, K3 new orbits
local norns = require("norns").reset()
local musicutil = require "musicutil"
engine.name = "PolyPerc"

local scale = musicutil.generate_scale(48, "Dorian", 3)
local planets, playing, flash = {}, true, 0

local function reroll()
  planets = {}
  for i = 1, 5 do
    planets[i] = { r = 6 + i * 5, a = math.random() * 2 * math.pi, steps = ({ 3, 4, 5, 6, 8 })[i], at = 0 }
  end
end

function init()
  math.randomseed(7)
  params:add_control("cutoff", "cutoff", controlspec.new(200, 6000, "exp", 0, 1200, "hz"))
  params:set_action("cutoff", function(v) engine.cutoff(v) end)
  params:add_number("tempo", "tempo", 40, 240, 110)
  params:set_action("tempo", function(v) params:set("clock_tempo", v) end)
  params:bang()
  engine.release(0.6); engine.amp(0.4)
  reroll()
  clock.run(function()
    while true do
      clock.sync(1 / 4)
      if playing then
        for i, p in ipairs(planets) do
          p.at = (p.at + 1) % p.steps
          p.a = p.a + 2 * math.pi / (p.steps * 4)
          if p.at == 0 then
            engine.pan(math.cos(p.a) * 0.6)
            engine.hz(musicutil.note_num_to_freq(scale[i * 2 + math.floor(p.a * 2) % 4]))
            p.flash = 4
          end
        end
      end
    end
  end)
  local m = metro.init(function()
    for _, p in ipairs(planets) do if (p.flash or 0) > 0 then p.flash = p.flash - 1 end end
    redraw()
  end, 1 / 15)
  m:start()
end

function redraw()
  screen.clear()
  screen.aa(0)
  screen.level(2)
  for _, p in ipairs(planets) do screen.circle(40, 32, p.r); screen.stroke() end
  screen.level(15); screen.circle(40, 32, 2); screen.fill()
  for _, p in ipairs(planets) do
    screen.level((p.flash or 0) > 0 and 15 or 6)
    screen.circle(40 + p.r * math.cos(p.a), 32 + p.r * math.sin(p.a), (p.flash or 0) > 0 and 3 or 2)
    screen.fill()
  end
  screen.level(10); screen.font_size(8)
  screen.move(80, 12); screen.text("orbits")
  screen.level(4)
  screen.move(80, 28); screen.text("bpm " .. params:get("tempo"))
  screen.move(80, 38); screen.text("cut " .. math.floor(params:get("cutoff")))
  screen.move(80, 54); screen.text(playing and "playing" or "stopped")
  screen.update()
end

function enc(n, d)
  if n == 2 then params:delta("tempo", d) elseif n == 3 then params:delta("cutoff", d) end
end

function key(n, z)
  if z == 0 then return end
  if n == 2 then playing = not playing elseif n == 3 then reroll() end
end

norns.start()   -- launch this box
```

## 4. noiz virus

<!-- field-box id="norns_noiz" index="3" -->

```edn [field-box id="norns_noiz" role="properties"]
{"id" "norns_noiz" "index" 3 "name" "noiz virus" "parents" ["root" "norns_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "norns_root"} "name" "noiz virus" "_tombstone" false "frame" #afield/rect [40.0 460.0 200.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "norns_noiz_code_.kt" "lines" 5 "characters" 360 "sha256" "8d203c72027251f5a92d79aee5f30393540585ac7fe5e6aa8dd0e013da2fdc83"}]}
```

```lua [field-property box="norns_noiz" name="code" path="norns_noiz_code_.kt"]
-- Launch: SPIKE the Percussionist's noiz virus, live. Fetch it first (the box above).
-- K2 plays and stops, K3 back to the start, E1 virus, E2 window, E3 mutation (the buttons
-- in the controls panel). The NoiZ Temple splash goes at the first key.
field.budget = 30
require("norns").run(os.getenv("HOME") .. "/.afield/norns/code/noiz_virus/noiz_virus.lua")
```

## 5. noiz virus, rendered

<!-- field-box id="norns_noiz_render" index="4" -->

```edn [field-box id="norns_noiz_render" role="properties"]
{"id" "norns_noiz_render" "index" 4 "name" "noiz virus, rendered" "parents" ["root" "norns_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "norns_root"} "name" "noiz virus, rendered" "_tombstone" false "frame" #afield/rect [280.0 460.0 200.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "norns_noiz_render_code_.kt" "lines" 15 "characters" 894 "sha256" "4eecde164abe3693bb83e6a2f0390e4597e17640b048a2cc55d8c65182bb94bb"}]}
```

```lua [field-property box="norns_noiz_render" name="code" path="norns_noiz_render_code_.kt"]
-- Run: noiz virus offline for 12 s: skip the splash, K2 at 3 s to play, E1 to another virus at 8 s.
field.budget = 60
local norns = require "norns"
local renders = os.getenv("HOME") .. "/.afield/norns/renders/"
local heard = norns.render {
  script = os.getenv("HOME") .. "/.afield/norns/code/noiz_virus/noiz_virus.lua", seconds = 12,
  events = { { 0.5, "key", 2, 1 }, { 0.6, "key", 2, 0 }, { 3, "key", 2, 1 }, { 3.1, "key", 2, 0 },
             { 8, "enc", 1, 3 } },
  screens = { { 0.2, renders .. "noiz_virus_splash.png" }, { 6, renders .. "noiz_virus_6s.png" } },
  wav = renders .. "noiz_virus.wav", png = renders .. "noiz_virus_end.png",
}
print(string.format("%.1f s, peak %.2f, rms %.3f, %d onsets", heard.seconds, heard.peak, heard.rms, heard.onsets))
local c = norns.coverage()
print("stubbed: " .. table.concat(c.stubbed, ", "))
print("missing: " .. table.concat(c.missing, ", "))
```

## 6. anima, rendered

<!-- field-box id="norns_anima_render" index="5" -->

```edn [field-box id="norns_anima_render" role="properties"]
{"id" "norns_anima_render" "index" 5 "name" "anima, rendered" "parents" ["root" "norns_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "norns_root"} "name" "anima, rendered" "_tombstone" false "frame" #afield/rect [520.0 460.0 200.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "norns_anima_render_code_.kt" "lines" 13 "characters" 699 "sha256" "096384740c0be46482425746b3d33018fd9328c636211e5a576e6822af4c6a16"}]}
```

```lua [field-property box="norns_anima_render" name="code" path="norns_anima_render_code_.kt"]
-- Run: om-i-god's anima offline for 10 s, its petals growing on the golden angle.
field.budget = 60
local norns = require "norns"
local renders = os.getenv("HOME") .. "/.afield/norns/renders/"
local heard = norns.render {
  script = os.getenv("HOME") .. "/.afield/norns/code/anima/anima.lua", seconds = 10,
  events = { { 1, "enc", 1, 4 }, { 3, "key", 2, 1 }, { 3.1, "key", 2, 0 } },
  wav = renders .. "anima.wav", png = renders .. "anima_end.png",
}
print(string.format("%.1f s, peak %.2f, rms %.3f, %d onsets", heard.seconds, heard.peak, heard.rms, heard.onsets))
local c = norns.coverage()
print("stubbed: " .. table.concat(c.stubbed, ", "))
print("missing: " .. table.concat(c.missing, ", "))
```

## 7. fetch the scripts

<!-- field-box id="norns_fetch" index="6" -->

```edn [field-box id="norns_fetch" role="properties"]
{"id" "norns_fetch" "index" 6 "name" "fetch the scripts" "parents" ["root" "norns_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "norns_root"} "name" "fetch the scripts" "_tombstone" false "frame" #afield/rect [40.0 320.0 200.0 80.0] "code_language" "Lua"} "blockProperties" [{"name" "code" "language" "lua" "path" "norns_fetch_code_.kt" "lines" 15 "characters" 774 "sha256" "e914526afcc1f8e890b618dfa868862d086e7e66eb6bfea36d648cd99f47011e"}]}
```

```lua [field-property box="norns_fetch" name="code" path="norns_fetch_code_.kt"]
-- Fetch two norns scripts from their authors' GitHub repositories into ~/.afield/norns/code/.
-- Neither repository states a licence, so Field does not ship them; this box clones them for you.
field.budget = 120
local code = os.getenv("HOME") .. "/.afield/norns/code/"
os.execute('mkdir -p "' .. code .. '"')
for _, repo in ipairs { "SPIKE-the-Percussionist/noiz_virus", "om-i-god/anima" } do
  local name = repo:match("/(.+)$")
  local dir = code .. name
  local f = io.open(dir .. "/" .. name .. ".lua")
  if f then f:close(); print(name, "already in", dir)
  else
    local ok = os.execute('git clone -q --depth 1 https://github.com/' .. repo .. ' "' .. dir .. '"')
    print(name, (ok == 0 or ok == true) and "cloned into" or "could not be cloned into", dir)
  end
end
```

