-- Field's norns host: the norns script API over a Lua box, loaded by `require "norns"`.
--
-- A norns script is a Lua file that defines init / redraw / key / enc / cleanup and draws on a 128x64
-- screen with `screen`, keeps its settings in `params`, times itself with `clock` and `metro`, and
-- plays an engine and softcut. This chunk defines those globals for a box's Lua. The screen and the
-- sound are Kotlin (afield/lang/lua/NornsHost.kt), reached through `raw`, the prelude's norns_*
-- handlers; everything else is here.
--
-- Time is the host's own: live, it follows the wall clock, one step per Field frame; in norns.render
-- it is virtual, stepped from one clock event to the next while Kotlin renders the sound between them.

local raw = ...
local type, tostring, tonumber, pairs, ipairs, select, unpack, error = type, tostring, tonumber, pairs, ipairs, select, unpack, error
local floor, ceil, abs, min, max, huge = math.floor, math.ceil, math.abs, math.min, math.max, math.huge
local co_create, co_resume, co_status, co_yield, co_running = coroutine.create, coroutine.resume, coroutine.status, coroutine.yield, coroutine.running

local M = {}            -- becomes the global `norns`
local used_stubs = {}   -- every stubbed function a script called, by name
local unhandled = {}    -- Lua-side calls nothing implements (an engine Field does not have)

local function stub(name, result)
  return function() used_stubs[name] = true; return result end
end

-- A table that accepts anything: indexing gives another sink, calling does nothing (crow, osc, hid).
local function sink(name)
  return setmetatable({}, {
    __index = function(_, k) used_stubs[name .. "." .. tostring(k)] = true; return sink(name .. "." .. tostring(k)) end,
    __newindex = function() used_stubs[name] = true end,
    __call = function() used_stubs[name] = true end,
  })
end

-- ── the host: time, clocks, metros ──────────────────────────────────────────────────────────────

local H = { mode = nil, now = 0, tasks = {}, metros = {}, next_id = 1, tempo = 120, beat_t = 0, beat_b = 0 }
M._host = H

local function beat_sec() return 60 / H.tempo end
local function beats_at(t) return H.beat_b + (t - H.beat_t) / beat_sec() end
local function time_of_beat(b) return H.beat_t + (b - H.beat_b) * beat_sec() end

local function set_tempo(bpm)
  bpm = max(1, tonumber(bpm) or 120)
  H.beat_b, H.beat_t = beats_at(H.now), H.now
  H.tempo = bpm
end

-- Where a task goes next, from what it yielded: ("sleep", s), ("sync", beats, offset) or nothing.
local function wake_after(t, kind, a, b)
  if kind == "sleep" then return t + max(tonumber(a) or 0, 1e-4)
  elseif kind == "sync" then
    local div = tonumber(a) or 1
    if div <= 0 then return t + 1e-4 end
    local off = tonumber(b) or 0
    local now_b = beats_at(t) - off
    local target = (floor(now_b / div + 1e-6) + 1) * div + off
    return time_of_beat(target)
  end
  return t + 1 / 100
end

local function resume(task)
  local ok, kind, a, b
  if task.args then
    local args = task.args; task.args = nil
    ok, kind, a, b = co_resume(task.co, unpack(args, 1, args.n))
  else
    ok, kind, a, b = co_resume(task.co)
  end
  if not ok then
    H.tasks[task.id] = nil
    error(debug.traceback(task.co, tostring(kind)), 0)
  end
  if co_status(task.co) == "dead" then H.tasks[task.id] = nil
  else task.wake = wake_after(task.wake, kind, a, b) end
end

local function fire(m)
  m.stage = m.stage + 1
  if m.count > 0 and m.stage > m.count then m.running = false; return end
  m.wake = m.wake + max(m.time, 1e-3)
  if m.event then m.event(m.stage) end
  if m.count > 0 and m.stage >= m.count then m.running = false end
end

local function earliest()
  local best, when, kind = nil, huge, nil
  for _, t in pairs(H.tasks) do if t.wake < when then best, when, kind = t, t.wake, "task" end end
  for _, m in pairs(H.metros) do if m.running and m.wake < when then best, when, kind = m, m.wake, "metro" end end
  return best, when, kind
end

--- Run every clock and metro due by time t (seconds since the host started), each at its own time.
local function step(t, before_each)
  local guard = 0
  while true do
    local item, when, kind = earliest()
    if not item or when > t then break end
    if before_each then before_each(when) end
    H.now = when
    if kind == "task" then resume(item) else fire(item) end
    guard = guard + 1
    if guard > 100000 then error("norns: more than 100000 clock events in one step (a clock that never sleeps?)", 0) end
  end
  H.now = max(H.now, t)
end
M._step = step

-- ── clock ───────────────────────────────────────────────────────────────────────────────────────

clock = {}
clock.threads = H.tasks
--- clock.run(fn, ...): run fn as a coroutine; clock.sleep and clock.sync inside it pause it. Returns its id.
function clock.run(fn, ...)
  if type(fn) ~= "function" then error("clock.run(fn, ...) needs a function", 2) end
  local id = H.next_id; H.next_id = id + 1
  H.tasks[id] = { id = id, co = co_create(fn), wake = H.now, args = { n = select('#', ...), ... } }
  return id
end
function clock.cancel(id) H.tasks[id] = nil end
function clock.sleep(s)
  if not co_running() then error("clock.sleep only works inside clock.run", 2) end
  return co_yield("sleep", s)
end
function clock.sync(beats, offset)
  if not co_running() then error("clock.sync only works inside clock.run", 2) end
  return co_yield("sync", beats, offset)
end
function clock.get_beats() return beats_at(H.now) end
function clock.get_tempo() return H.tempo end
function clock.get_beat_sec() return beat_sec() end
function clock.set_source() used_stubs["clock.set_source"] = true end
clock.internal = { set_tempo = set_tempo, start = function() end, stop = function() end }
clock.transport = {}
clock.link = sink("clock.link")
clock.midi = sink("clock.midi")
clock.crow = sink("clock.crow")
function clock.add_params() end
function clock.cleanup() for id in pairs(H.tasks) do H.tasks[id] = nil end end

-- ── metro ───────────────────────────────────────────────────────────────────────────────────────

local Metro = {}
Metro.__index = Metro
function Metro:start(time, count, stage)
  if type(time) == "table" then time, count, stage = time.time, time.count, time.stage end
  if time then self.time = time end
  if count then self.count = count end
  self.stage = (stage or 1) - 1
  self.running = true
  self.wake = H.now + max(self.time, 1e-3)
end
function Metro:stop() self.running = false end

metro = { metros = H.metros, num_metros = 36 }
--- metro.init(event, time, count) or metro.init{event=, time=, count=}: a repeating timer, stopped until :start().
function metro.init(event, time, count)
  if type(event) == "table" then event, time, count = event.event, event.time, event.count end
  local id = #H.metros + 1
  local m = setmetatable({ id = id, event = event, time = time or 1, count = count or -1, stage = 0, running = false, wake = 0 }, Metro)
  H.metros[id] = m
  return m
end
function metro.free(id) if H.metros[id] then H.metros[id].running = false; H.metros[id] = nil end end
function metro.free_all() for id in pairs(H.metros) do H.metros[id] = nil end end
setmetatable(metro, { __index = function(_, i) return type(i) == "number" and H.metros[i] or nil end })

-- ── screen ──────────────────────────────────────────────────────────────────────────────────────

local ops = {}
local screen_opts = { show = true }
local font_size = 8
screen = {}
local function op(name) return function(...) ops[#ops + 1] = { name, ... } end end
for _, name in ipairs { "level", "aa", "line_width", "move", "move_rel", "line", "line_rel", "curve", "curve_rel",
                        "arc", "circle", "rect", "close", "stroke", "fill", "pixel", "clear", "font_face" } do
  screen[name] = op(name)
end
function screen.font_size(s) font_size = tonumber(s) or 8; ops[#ops + 1] = { "font_size", font_size } end
function screen.text(s) ops[#ops + 1] = { "text", tostring(s) } end
function screen.text_right(s) ops[#ops + 1] = { "text_right", tostring(s) } end
function screen.text_center(s) ops[#ops + 1] = { "text_center", tostring(s) } end
function screen.text_rotate(x, y, s) used_stubs["screen.text_rotate (drawn unrotated)"] = true; ops[#ops + 1] = { "move", x, y }; ops[#ops + 1] = { "text", tostring(s) } end
function screen.text_center_rotate(x, y, s) used_stubs["screen.text_center_rotate (drawn unrotated)"] = true; ops[#ops + 1] = { "move", x, y }; ops[#ops + 1] = { "text_center", tostring(s) } end
function screen.display_png(path, x, y) ops[#ops + 1] = { "display_png", tostring(path), x or 0, y or 0 } end
--- The width and height of s in the current font, in pixels.
function screen.text_extents(s) return raw.text_width(tostring(s), font_size), font_size end
--- Draw what has been queued since the last update and show it.
function screen.update()
  local o = ops; ops = {}
  local bad = raw.screen(o, screen_opts)
  for _, name in ipairs(bad or {}) do unhandled["screen." .. name] = true end
end
screen.update_default = screen.update
--- screen.peek(x, y, w, h): the region's levels, one byte 0-15 a pixel, as norns returns them.
function screen.peek(x, y, w, h) screen.update(); return raw.peek(x, y, w, h) end
screen.ping = function() end
screen.sleep, screen.wake = function() end, function() end
for _, name in ipairs { "line_cap", "line_join", "miter_limit", "blend_mode", "save", "restore", "translate", "rotate",
                        "load_png", "display_image", "display_image_region", "create_image", "poke", "current_point", "export_png", "record" } do
  screen[name] = stub("screen." .. name)
end

-- ── engine, softcut, audio: commands to the Kotlin sound ────────────────────────────────────────

local ENGINES = { PolyPerc = true }
engine = setmetatable({ name = nil }, {
  __index = function(t, k)
    return function(...)
      local name = rawget(t, "name")
      if ENGINES[name] then raw.cmd("engine", k, { ... })
      else unhandled["engine " .. tostring(name) .. "." .. tostring(k)] = true end
    end
  end,
})

softcut = setmetatable({ BUFFER_SIZE = 60, VOICE_COUNT = 6 }, {
  __index = function(_, k) return function(...) raw.cmd("softcut", k, { ... }) end end,
})
for _, name in ipairs { "event_phase", "event_render", "event_position", "poll_start_phase", "poll_stop_phase",
                        "phase_quant", "phase_offset", "render_buffer", "query_position", "voice_sync", "fade_time",
                        "post_filter_dry", "post_filter_lp", "post_filter_hp", "post_filter_bp", "post_filter_br",
                        "post_filter_fc", "post_filter_rq", "pre_filter_dry", "pre_filter_lp", "pre_filter_fc",
                        "pre_filter_rq", "pre_filter_fc_mod", "pan_slew_time", "recpre_slew_time", "level_cut_cut",
                        "buffer_write_mono", "buffer_write_stereo", "buffer_copy_mono", "buffer_copy_stereo" } do
  softcut[name] = stub("softcut." .. name)
end

audio = setmetatable({}, {
  __index = function(_, k) return function(...) raw.cmd("audio", k, { ... }) end end,
})
--- audio.file_info(path): channels, frames, sample rate.
function audio.file_info(path) return raw.file_info(path) end
for _, name in ipairs { "rev_on", "rev_off", "comp_on", "comp_off", "level_monitor", "level_monitor_rev", "level_eng_rev",
                        "level_cut_rev", "level_adc_cut", "level_tape_cut", "level_adc", "level_tape", "level_rev_dac",
                        "monitor_mono", "monitor_stereo", "pitch_on", "pitch_off", "rev_param", "comp_param", "headphone_gain",
                        "level_ext", "level_dac", "tape_record_open", "tape_record_start", "tape_record_stop", "tape_play_open",
                        "tape_play_start", "tape_play_stop" } do
  audio[name] = stub("audio." .. name)
end

-- ── util ────────────────────────────────────────────────────────────────────────────────────────

util = {}
function util.clamp(n, lo, hi) return min(max(n, lo), hi) end
function util.round(n, q) q = q or 1; return floor(n / q + 0.5) * q end
function util.round_up(n, q) q = q or 1; return ceil(n / q) * q end
function util.linlin(slo, shi, dlo, dhi, f)
  if f <= slo then return dlo elseif f >= shi then return dhi end
  return (f - slo) / (shi - slo) * (dhi - dlo) + dlo
end
function util.linexp(slo, shi, dlo, dhi, f)
  if f <= slo then return dlo elseif f >= shi then return dhi end
  return (dhi / dlo) ^ ((f - slo) / (shi - slo)) * dlo
end
function util.explin(slo, shi, dlo, dhi, f)
  if f <= slo then return dlo elseif f >= shi then return dhi end
  return math.log(f / slo) / math.log(shi / slo) * (dhi - dlo) + dlo
end
function util.expexp(slo, shi, dlo, dhi, f)
  if f <= slo then return dlo elseif f >= shi then return dhi end
  return (dhi / dlo) ^ (math.log(f / slo) / math.log(shi / slo)) * dlo
end
function util.wrap(n, lo, hi)
  if hi < lo then lo, hi = hi, lo end
  local range = hi - lo + 1
  return lo + ((n - lo) % range)
end
function util.wrap_max(n, lo, hi) return lo + ((n - lo) % (hi - lo)) end
function util.file_exists(p) local f = io.open(p, "r"); if f then f:close(); return true end; return false end
function util.make_dir(p) os.execute('mkdir -p "' .. tostring(p) .. '"') end
function util.time() return field.clock() end
function util.s_to_hms(s) s = floor(s); return string.format("%d:%02d:%02d", floor(s / 3600), floor(s / 60) % 60, s % 60) end
function util.string_starts(s, start) return string.sub(s, 1, #start) == start end
function util.trim_string_to_width(s, w)
  while #s > 0 and screen.text_extents(s) > w do s = s:sub(1, -2) end
  return s
end
function util.os_capture(cmd)
  local f = io.popen(cmd, "r"); if not f then return "" end
  local s = f:read("*a"); f:close()
  return (s:gsub("^%s+", ""):gsub("%s+$", ""):gsub("[\n\r]+", " "))
end
function util.scandir(dir)
  local out, f = {}, io.popen('ls -p "' .. tostring(dir) .. '" 2>/dev/null')
  if f then for l in f:lines() do out[#out + 1] = l end; f:close() end
  return out
end
function util.dbamp(db) return 10 ^ (db / 20) end
function util.ampdb(a) return 20 * math.log10(a) end
function util.degs_to_rads(d) return d * math.pi / 180 end
function util.rads_to_degs(r) return r * 180 / math.pi end
function util.acronym(s) return (s:gsub("(%w)%w*%s*", "%1")) end
function util.round_down(n, q) q = q or 1; return floor(n / q) * q end

-- ── tab ─────────────────────────────────────────────────────────────────────────────────────────

tab = {}
function tab.print(t) for k, v in pairs(t) do print(tostring(k) .. "\t" .. tostring(v)) end end
function tab.count(t) local n = 0; for _ in pairs(t) do n = n + 1 end; return n end
function tab.contains(t, e) for _, v in pairs(t) do if v == e then return true end end; return false end
function tab.key(t, e) for k, v in pairs(t) do if v == e then return k end end; return nil end
function tab.invert(t) local o = {}; for k, v in pairs(t) do o[v] = k end; return o end
function tab.sort(t) local keys = {}; for k in pairs(t) do keys[#keys + 1] = k end; table.sort(keys); return keys end
function tab.split(s, sep)
  local out = {}
  for piece in string.gmatch(s, "([^" .. sep .. "]+)") do out[#out + 1] = piece end
  return out
end
function tab.gather(a, b) local o = {}; for k, v in pairs(a) do o[k] = b[k] == nil and v or b[k] end; return o end
function tab.update(a, b) for k, v in pairs(b) do a[k] = v end; return a end
function tab.select_values(t, f) local o = {}; for k, v in pairs(t) do if f(v) then o[k] = v end end; return o end
function tab.readonly(p) return setmetatable({}, { __index = p.table, __newindex = function() error("read-only", 2) end }) end
function tab.lines(s) local o = {}; for l in string.gmatch(s, "[^\n]+") do o[#o + 1] = l end; return o end
tab.save = stub("tab.save")
tab.load = stub("tab.load")

-- ── controlspec and params ──────────────────────────────────────────────────────────────────────

controlspec = {}
controlspec.__index = controlspec
--- controlspec.new(min, max, warp, step, default, units, quantum, wrap)
function controlspec.new(minval, maxval, warp, step, default, units, quantum, wrap)
  local s = setmetatable({}, controlspec)
  s.minval, s.maxval = minval or 0, maxval or 1
  s.warp = type(warp) == "string" and warp or "lin"
  s.step = step or 0
  s.default = default or s.minval
  s.units = units or ""
  s.quantum = quantum or 0.01
  s.wrap = wrap or false
  return s
end
controlspec.def = function(t) return controlspec.new(t.min, t.max, t.warp, t.step, t.default, t.units, t.quantum, t.wrap) end
function controlspec:map(v)
  v = min(max(v, 0), 1)
  local r
  if self.warp == "exp" and self.minval > 0 and self.maxval > 0 then r = self.minval * (self.maxval / self.minval) ^ v
  else r = self.minval + (self.maxval - self.minval) * v end
  if self.step and self.step > 0 then r = floor(r / self.step + 0.5) * self.step end
  return r
end
function controlspec:unmap(v)
  local lo, hi = min(self.minval, self.maxval), max(self.minval, self.maxval)
  v = min(max(v, lo), hi)
  if self.warp == "exp" and self.minval > 0 and self.maxval > 0 then return math.log(v / self.minval) / math.log(self.maxval / self.minval) end
  if self.maxval == self.minval then return 0 end
  return (v - self.minval) / (self.maxval - self.minval)
end
function controlspec:constrain(v) return self:map(self:unmap(v)) end
function controlspec:copy() return controlspec.new(self.minval, self.maxval, self.warp, self.step, self.default, self.units, self.quantum, self.wrap) end
controlspec.UNIPOLAR = controlspec.new(0, 1, "lin", 0, 0)
controlspec.BIPOLAR = controlspec.new(-1, 1, "lin", 0, 0)
controlspec.FREQ = controlspec.new(20, 20000, "exp", 0, 440, "Hz")
controlspec.LOFREQ = controlspec.new(0.1, 100, "exp", 0, 6, "Hz")
controlspec.MIDFREQ = controlspec.new(25, 4200, "exp", 0, 440, "Hz")
controlspec.WIDEFREQ = controlspec.new(0.1, 20000, "exp", 0, 440, "Hz")
controlspec.PHASE = controlspec.new(0, math.pi, "lin", 0, 0)
controlspec.RQ = controlspec.new(0.001, 2, "exp", 0, 0.707)
controlspec.MIDINOTE = controlspec.new(0, 127, "lin", 1, 60)
controlspec.MIDIVELOCITY = controlspec.new(1, 127, "lin", 1, 64)
controlspec.AMP = controlspec.new(0, 1, "lin", 0, 0)
controlspec.BOOSTAMP = controlspec.new(0, 10, "lin", 0, 1)
controlspec.PAN = controlspec.new(-1, 1, "lin", 0, 0)
controlspec.DELAY = controlspec.new(0.0001, 1, "exp", 0, 0.3, "secs")
controlspec.DB = controlspec.new(-60, 0, "lin", 0, 0, "dB")

local P = {}
P.__index = P
local T = { tSEPARATOR = 0, tNUMBER = 1, tOPTION = 2, tCONTROL = 3, tFILE = 4, tTAPER = 5, tTRIGGER = 6, tGROUP = 7, tTEXT = 8, tBINARY = 9 }

local function fmt_num(v) if v == floor(v) then return tostring(floor(v)) end return string.format("%.2f", v) end
function P:get() return self.value end
function P:string()
  if self.formatter then return self.formatter(self) end
  if self.t == T.tOPTION then return tostring(self.options[self.value]) end
  if self.t == T.tCONTROL or self.t == T.tTAPER then return fmt_num(self.value) .. " " .. (self.units or "") end
  return tostring(self.value)
end
function P:bang() if self.action then self.action(self.value) end end
function P:set(v, silent)
  local t = self.t
  if t == T.tTRIGGER then if not silent and self.action then self.action(1) end return end
  if t == T.tNUMBER then
    v = floor((tonumber(v) or 0) + 0.5)
    if self.wrap then v = util.wrap(v, self.min, self.max) else v = min(max(v, self.min), self.max) end
  elseif t == T.tOPTION then
    v = min(max(floor((tonumber(v) or 1) + 0.5), 1), #self.options)
  elseif t == T.tCONTROL or t == T.tTAPER then
    v = self.controlspec:constrain(tonumber(v) or 0); self.raw = self.controlspec:unmap(v)
  elseif t == T.tBINARY then
    v = (v == true or v == 1) and 1 or 0
  end
  if v ~= self.value then
    self.value = v
    if not silent then self:bang() end
  end
end
function P:delta(d)
  local t = self.t
  if t == T.tNUMBER or t == T.tOPTION then self:set(self.value + d)
  elseif t == T.tCONTROL or t == T.tTAPER then
    local cs = self.controlspec
    local r = self.raw + d * cs.quantum
    if cs.wrap then r = r % 1 end
    self:set_raw(r)
  elseif t == T.tBINARY then self:set(d > 0 and 1 or 0)
  elseif t == T.tTRIGGER then if d ~= 0 then self:set(1) end
  end
end
function P:get_raw() return self.raw or 0 end
function P:set_raw(r, silent)
  r = min(max(r, 0), 1)
  local v = self.controlspec:map(r)
  self.raw = r
  if v ~= self.value then self.value = v; if not silent then self:bang() end end
end
function P:get_delta() return 1 end

local ParamSet = {}
ParamSet.__index = ParamSet
for k, v in pairs(T) do ParamSet[k] = v end

local function new_params() return setmetatable({ params = {}, lookup = {}, count = 0, hidden = {}, group = 0 }, ParamSet) end

function ParamSet:_add(p)
  self.count = self.count + 1
  self.params[self.count] = p
  if p.id then self.lookup[p.id] = self.count end
  return p
end
--- params:add{type=, id=, name=, ...}: any param type from one table, as norns takes it.
function ParamSet:add(a)
  local t = a.type
  local p
  if t == "number" then p = self:add_number(a.id, a.name, a.min, a.max, a.default, a.formatter, a.wrap)
  elseif t == "option" then p = self:add_option(a.id, a.name, a.options, a.default)
  elseif t == "control" then p = self:add_control(a.id, a.name, a.controlspec, a.formatter)
  elseif t == "taper" then p = self:add_taper(a.id, a.name, a.min, a.max, a.default, a.k, a.units)
  elseif t == "trigger" then p = self:add_trigger(a.id, a.name)
  elseif t == "binary" then p = self:add_binary(a.id, a.name, a.behavior, a.default)
  elseif t == "file" then p = self:add_file(a.id, a.name, a.path)
  elseif t == "text" then p = self:add_text(a.id, a.name, a.text)
  elseif t == "separator" then p = self:add_separator(a.id, a.name)
  elseif t == "group" then p = self:add_group(a.id, a.name, a.n)
  else error("params:add: unknown type " .. tostring(t), 2) end
  if a.action then p.action = a.action end
  if a.formatter then p.formatter = a.formatter end
  if a.allow_pmap ~= nil then p.allow_pmap = a.allow_pmap end
  return p
end
function ParamSet:add_number(id, name, lo, hi, default, formatter, wrap)
  lo, hi = lo or -huge, hi or huge
  return self:_add(setmetatable({ t = T.tNUMBER, id = id, name = name or id, min = lo, max = hi, value = default or (lo > -huge and lo or 0), default = default, formatter = formatter, wrap = wrap }, P))
end
function ParamSet:add_option(id, name, options, default)
  return self:_add(setmetatable({ t = T.tOPTION, id = id, name = name or id, options = options or {}, count = #(options or {}), value = default or 1, default = default or 1 }, P))
end
function ParamSet:add_control(id, name, cs, formatter)
  cs = cs or controlspec.UNIPOLAR
  local p = setmetatable({ t = T.tCONTROL, id = id, name = name or id, controlspec = cs, formatter = formatter, units = cs.units }, P)
  p.value = cs:constrain(cs.default); p.raw = cs:unmap(p.value); p.default = p.value
  return self:_add(p)
end
function ParamSet:add_taper(id, name, lo, hi, default, k, units)
  local cs = controlspec.new(lo or 0, hi or 1, (lo or 0) > 0 and "exp" or "lin", 0, default or lo or 0, units)
  local p = self:add_control(id, name, cs)
  p.t = T.tTAPER
  return p
end
function ParamSet:add_trigger(id, name) return self:_add(setmetatable({ t = T.tTRIGGER, id = id, name = name or id, value = 0 }, P)) end
function ParamSet:add_binary(id, name, behavior, default)
  return self:_add(setmetatable({ t = T.tBINARY, id = id, name = name or id, behavior = behavior or "trigger", value = default or 0, default = default or 0 }, P))
end
function ParamSet:add_file(id, name, path) return self:_add(setmetatable({ t = T.tFILE, id = id, name = name or id, value = path or "", path = path }, P)) end
function ParamSet:add_text(id, name, text) return self:_add(setmetatable({ t = T.tTEXT, id = id, name = name or id, value = text or "" }, P)) end
function ParamSet:add_separator(id, name)
  return self:_add(setmetatable({ t = T.tSEPARATOR, id = name and id or nil, name = name or id or "" }, P))
end
function ParamSet:add_group(id, name, n)
  if type(name) == "number" then id, name, n = nil, id, name end
  return self:_add(setmetatable({ t = T.tGROUP, id = id, name = name, n = n }, P))
end
function ParamSet:lookup_param(id)
  local i = type(id) == "number" and id or self.lookup[id]
  local p = i and self.params[i]
  if not p then error("params: no parameter '" .. tostring(id) .. "'", 3) end
  return p
end
function ParamSet:get(id) return self:lookup_param(id):get() end
function ParamSet:set(id, v, silent) return self:lookup_param(id):set(v, silent) end
function ParamSet:string(id) return self:lookup_param(id):string() end
function ParamSet:delta(id, d) return self:lookup_param(id):delta(d) end
function ParamSet:get_raw(id) return self:lookup_param(id):get_raw() end
function ParamSet:set_raw(id, r, silent) return self:lookup_param(id):set_raw(r, silent) end
function ParamSet:set_action(id, f) self:lookup_param(id).action = f end
function ParamSet:get_id(i) return self.params[i] and self.params[i].id end
function ParamSet:t(id) return self:lookup_param(id).t end
function ParamSet:visible(id) return not self.hidden[id] end
function ParamSet:hide(id) self.hidden[id] = true end
function ParamSet:show(id) self.hidden[id] = nil end
function ParamSet:bang() for _, p in ipairs(self.params) do if p.t ~= T.tTRIGGER and p.t ~= T.tSEPARATOR and p.t ~= T.tGROUP then p:bang() end end end
function ParamSet:default() for _, p in ipairs(self.params) do if p.default ~= nil then p:set(p.default, true) end end end
function ParamSet:clear() local fresh = new_params(); for k, v in pairs(fresh) do self[k] = v end end
ParamSet.read = stub("params:read")
ParamSet.write = stub("params:write")
ParamSet.add_pset = stub("params:add_pset")
--- Every parameter as `name: value`, for printing.
function ParamSet:list()
  local out = {}
  for _, p in ipairs(self.params) do
    if p.t ~= T.tSEPARATOR and p.t ~= T.tGROUP then out[#out + 1] = (p.name or p.id) .. ": " .. p:string() end
  end
  return out
end

-- ── musicutil ───────────────────────────────────────────────────────────────────────────────────

local MU = {}
MU.NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" }
MU.SCALES = {
  { name = "Major", alt_names = { "Ionian" }, intervals = { 0, 2, 4, 5, 7, 9, 11, 12 } },
  { name = "Natural Minor", alt_names = { "Minor", "Aeolian" }, intervals = { 0, 2, 3, 5, 7, 8, 10, 12 } },
  { name = "Harmonic Minor", intervals = { 0, 2, 3, 5, 7, 8, 11, 12 } },
  { name = "Melodic Minor", intervals = { 0, 2, 3, 5, 7, 9, 11, 12 } },
  { name = "Dorian", intervals = { 0, 2, 3, 5, 7, 9, 10, 12 } },
  { name = "Phrygian", intervals = { 0, 1, 3, 5, 7, 8, 10, 12 } },
  { name = "Lydian", intervals = { 0, 2, 4, 6, 7, 9, 11, 12 } },
  { name = "Mixolydian", intervals = { 0, 2, 4, 5, 7, 9, 10, 12 } },
  { name = "Locrian", intervals = { 0, 1, 3, 5, 6, 8, 10, 12 } },
  { name = "Whole Tone", intervals = { 0, 2, 4, 6, 8, 10, 12 } },
  { name = "Major Pentatonic", alt_names = { "Gagaku Ryo Sen Pou" }, intervals = { 0, 2, 4, 7, 9, 12 } },
  { name = "Minor Pentatonic", alt_names = { "Zokugaku Yo Sen Pou" }, intervals = { 0, 3, 5, 7, 10, 12 } },
  { name = "Major Bebop", intervals = { 0, 2, 4, 5, 7, 8, 9, 11, 12 } },
  { name = "Altered Scale", intervals = { 0, 1, 3, 4, 6, 8, 10, 12 } },
  { name = "Dorian Bebop", intervals = { 0, 2, 3, 4, 5, 7, 9, 10, 12 } },
  { name = "Mixolydian Bebop", intervals = { 0, 2, 4, 5, 7, 9, 10, 11, 12 } },
  { name = "Blues Scale", alt_names = { "Blues" }, intervals = { 0, 3, 5, 6, 7, 10, 12 } },
  { name = "Diminished Whole Half", intervals = { 0, 2, 3, 5, 6, 8, 9, 11, 12 } },
  { name = "Diminished Half Whole", intervals = { 0, 1, 3, 4, 6, 7, 9, 10, 12 } },
  { name = "Neapolitan Major", intervals = { 0, 1, 3, 5, 7, 9, 11, 12 } },
  { name = "Hungarian Major", intervals = { 0, 3, 4, 6, 7, 9, 10, 12 } },
  { name = "Harmonic Major", intervals = { 0, 2, 4, 5, 7, 8, 11, 12 } },
  { name = "Hungarian Minor", intervals = { 0, 2, 3, 6, 7, 8, 11, 12 } },
  { name = "Lydian Minor", intervals = { 0, 2, 4, 6, 7, 8, 10, 12 } },
  { name = "Neapolitan Minor", alt_names = { "Byzantine" }, intervals = { 0, 1, 3, 5, 7, 8, 11, 12 } },
  { name = "Major Locrian", intervals = { 0, 2, 4, 5, 6, 8, 10, 12 } },
  { name = "Leading Whole Tone", intervals = { 0, 2, 4, 6, 8, 10, 11, 12 } },
  { name = "Six Tone Symmetrical", intervals = { 0, 1, 4, 5, 8, 9, 11, 12 } },
  { name = "Balinese", intervals = { 0, 1, 3, 7, 8, 12 } },
  { name = "Persian", intervals = { 0, 1, 4, 5, 6, 8, 11, 12 } },
  { name = "East Indian Purvi", intervals = { 0, 1, 4, 6, 7, 8, 11, 12 } },
  { name = "Oriental", intervals = { 0, 1, 4, 5, 6, 9, 10, 12 } },
  { name = "Double Harmonic", intervals = { 0, 1, 4, 5, 7, 8, 11, 12 } },
  { name = "Enigmatic", intervals = { 0, 1, 4, 6, 8, 10, 11, 12 } },
  { name = "Overtone", intervals = { 0, 2, 4, 6, 7, 9, 10, 12 } },
  { name = "Eight Tone Spanish", intervals = { 0, 1, 3, 4, 5, 6, 8, 10, 12 } },
  { name = "Prometheus", intervals = { 0, 2, 4, 6, 9, 10, 12 } },
  { name = "Gagaku Rittsu Sen Pou", intervals = { 0, 2, 5, 7, 9, 10, 12 } },
  { name = "In Sen Pou", intervals = { 0, 1, 5, 2, 8, 12 } },
  { name = "Okinawa", intervals = { 0, 4, 5, 7, 11, 12 } },
  { name = "Chromatic", intervals = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 } },
}
local function find_scale(kind)
  if type(kind) == "number" then return MU.SCALES[kind] end
  local want = tostring(kind):lower()
  for _, s in ipairs(MU.SCALES) do
    if s.name:lower() == want then return s end
    for _, a in ipairs(s.alt_names or {}) do if a:lower() == want then return s end end
  end
end
function MU.generate_scale_of_length(root, kind, length)
  local s = find_scale(kind) or MU.SCALES[1]
  local out, i, octave = {}, 1, 0
  local steps = #s.intervals - 1
  while #out < length do
    out[#out + 1] = root + octave * s.intervals[#s.intervals] + s.intervals[i]
    i = i + 1
    if i > steps then i = 1; octave = octave + 1 end
  end
  return out
end
function MU.generate_scale(root, kind, octaves)
  local s = find_scale(kind) or MU.SCALES[1]
  return MU.generate_scale_of_length(root, kind, (#s.intervals - 1) * (octaves or 1) + 1)
end
function MU.note_num_to_freq(n) return 440 * 2 ^ ((n - 69) / 12) end
function MU.freq_to_note_num(f) return util.clamp(floor(12 * math.log(f / 440) / math.log(2) + 69.5), 0, 127) end
function MU.note_num_to_name(n, octave)
  local name = MU.NOTE_NAMES[n % 12 + 1]
  if octave then name = name .. tostring(floor(n / 12) - 1) end
  return name
end
function MU.note_nums_to_names(t, octave) local o = {}; for i, n in ipairs(t) do o[i] = MU.note_num_to_name(n, octave) end; return o end
function MU.snap_note_to_array(n, arr)
  local best, dist = arr[1], huge
  for _, v in ipairs(arr) do if abs(v - n) < dist then best, dist = v, abs(v - n) end end
  return best
end
function MU.interval_to_ratio(i) return 2 ^ (i / 12) end
function MU.ratio_to_interval(r) return 12 * math.log(r) / math.log(2) end
musicutil = MU

-- ── grid and arc: virtual devices drawn in the box's output ─────────────────────────────────────

local devices = { grids = {}, arcs = {} }
local function show(channel, html, last)
  if screen_opts.show == false then return last end
  if html ~= last then display(channel, html) end
  return html
end

local Grid = {}
Grid.__index = Grid
function Grid:led(x, y, l) if x >= 1 and x <= self.cols and y >= 1 and y <= self.rows then self.leds[(y - 1) * self.cols + x] = l end end
function Grid:all(l) for i = 1, self.cols * self.rows do self.leds[i] = l end end
function Grid:refresh()
  local parts = { '<svg width="', self.cols * 18, '" height="', self.rows * 18, '" style="background:#111; border-radius:4px">' }
  for y = 1, self.rows do for x = 1, self.cols do
    local l = self.leds[(y - 1) * self.cols + x] or 0
    local g = floor(30 + l * 15)
    parts[#parts + 1] = string.format('<rect x="%d" y="%d" width="14" height="14" rx="2" fill="rgb(%d,%d,%d)"/>', (x - 1) * 18 + 2, (y - 1) * 18 + 2, g, floor(g * 0.85), floor(g * 0.5))
  end end
  parts[#parts + 1] = "</svg>"
  self.last = show("grid", table.concat(parts), self.last)
end
Grid.rotation, Grid.intensity, Grid.tilt_enable = stub("grid:rotation"), stub("grid:intensity"), stub("grid:tilt_enable")
grid = { vports = {} }
--- grid.connect(n): a virtual 16x8 grid shown in the box's `grid` panel; norns.grid_key presses it.
function grid.connect(n)
  n = n or 1
  if not devices.grids[n] then
    local cols, rows = 16, 8
    local leds = {}
    for i = 1, cols * rows do leds[i] = 0 end
    devices.grids[n] = setmetatable({ cols = cols, rows = rows, leds = leds, name = "field grid", device = true }, Grid)
    grid.vports[n] = devices.grids[n]
  end
  return devices.grids[n]
end

local Arc = {}
Arc.__index = Arc
function Arc:led(ring, x, l) if self.leds[ring] then self.leds[ring][((x - 1) % 64) + 1] = l end end
function Arc:all(l) for r = 1, 4 do for i = 1, 64 do self.leds[r][i] = l end end end
function Arc:segment(ring, from, to, l)
  local a, b = floor(from / (2 * math.pi) * 64), floor(to / (2 * math.pi) * 64)
  for i = a, b do self:led(ring, i + 1, l) end
end
function Arc:refresh()
  local parts = { '<svg width="360" height="96" style="background:#111; border-radius:4px">' }
  for r = 1, 4 do
    local cx = (r - 1) * 90 + 45
    for i = 1, 64 do
      local a = (i - 1) / 64 * 2 * math.pi - math.pi / 2
      local g = floor(30 + (self.leds[r][i] or 0) * 15)
      parts[#parts + 1] = string.format('<circle cx="%.1f" cy="%.1f" r="2.4" fill="rgb(%d,%d,%d)"/>', cx + 36 * math.cos(a), 48 + 36 * math.sin(a), g, floor(g * 0.85), floor(g * 0.5))
    end
  end
  parts[#parts + 1] = "</svg>"
  self.last = show("arc", table.concat(parts), self.last)
end
arc = { vports = {} }
--- arc.connect(n): a virtual four-ring arc in the box's `arc` panel; norns.arc_delta turns it.
function arc.connect(n)
  n = n or 1
  if not devices.arcs[n] then
    local leds = {}
    for r = 1, 4 do leds[r] = {}; for i = 1, 64 do leds[r][i] = 0 end end
    devices.arcs[n] = setmetatable({ leds = leds, name = "field arc", device = true }, Arc)
    arc.vports[n] = devices.arcs[n]
  end
  return devices.arcs[n]
end

-- ── midi and the rest: stubs ────────────────────────────────────────────────────────────────────

local MidiDevice = {}
MidiDevice.__index = function(_, k) used_stubs["midi:" .. tostring(k)] = true; return function() end end
midi = { vports = {}, devices = {} }
function midi.connect(n) n = n or 1; midi.vports[n] = midi.vports[n] or setmetatable({ name = "none", device = nil }, MidiDevice); return midi.vports[n] end
midi.to_msg = stub("midi.to_msg", {})
midi.to_data = stub("midi.to_data", {})
crow = sink("crow")
osc = sink("osc")
hid = sink("hid")
keyboard = sink("keyboard")
poll = sink("poll")
gamepad = sink("gamepad")

-- ── the script: loading, lifecycle, driving ─────────────────────────────────────────────────────

local HOME = os.getenv("HOME") or "/tmp"
_path = { home = HOME .. "/", dust = HOME .. "/.afield/norns/", code = HOME .. "/.afield/norns/code/",
          data = HOME .. "/.afield/norns/data/", audio = HOME .. "/.afield/norns/audio/", tape = HOME .. "/.afield/norns/audio/tape/" }
M.state = { path = "", name = "", shortname = "", data = _path.data, lib = "" }
M.version = { update = "field" }
M.script = { clear = function() end, load = function() end }

local function dirname(p) return (p:match("^(.*)/[^/]*$") or ".") .. "/" end

-- ── Lua 5.3 → LuaJIT: norns runs Lua 5.3, Field's Lua is LuaJIT (5.1) ──────────────────────────
--
-- The one piece of 5.3 syntax scripts reach for is floor division, `a // b`. It is rewritten to
-- `math.floor((a) / (b))` token by token: the right operand is one term (unary operators, a primary
-- expression and its calls and indexes, a `^` chain); the left is the run of terms joined by `*`, `/`
-- and `%` before it, since `//` shares their precedence and associates left. Strings, long strings
-- and comments are skipped, and line breaks are kept, so errors keep their lines. Bitwise operators
-- are not translated. The library differences are shimmed below (table.unpack, math.tointeger, ...).

local function tokens(src)
  local out, i, n = {}, 1, #src
  local function push(kind, a, b) out[#out + 1] = { kind = kind, a = a, b = b, text = src:sub(a, b) } end
  while i <= n do
    local c = src:sub(i, i)
    if c:match("%s") then
      local j = src:find("[^%s]", i) or n + 1; i = j
    elseif src:sub(i, i + 1) == "--" then
      local eq = src:match("^%-%-%[(=*)%[", i)
      if eq then
        local _, e = src:find("]" .. eq .. "]", i, true); i = (e or n) + 1
      else
        local e = src:find("\n", i, true); i = (e or n) + 1
      end
    elseif c == '"' or c == "'" then
      local j = i + 1
      while j <= n do
        local d = src:sub(j, j)
        if d == "\\" then j = j + 2 elseif d == c then break else j = j + 1 end
      end
      push("str", i, j); i = j + 1
    elseif c == "[" and src:match("^%[=*%[", i) then
      local eq = src:match("^%[(=*)%[", i)
      local _, e = src:find("]" .. eq .. "]", i, true)
      push("str", i, e or n); i = (e or n) + 1
    elseif c:match("[%a_]") then
      local _, e = src:find("^[%w_]+", i); push("name", i, e); i = e + 1
    elseif c:match("%d") or (c == "." and src:sub(i + 1, i + 1):match("%d")) then
      local _, e = src:find("^0[xX][%x%.]+[pP]?[%+%-]?%x*", i)
      if not e then _, e = src:find("^[%d%.]+[eE][%+%-]?%d+", i) end
      if not e then _, e = src:find("^[%d%.]+", i) end
      push("num", i, e); i = e + 1
    else
      local three, two = src:sub(i, i + 2), src:sub(i, i + 1)
      if three == "..." then push("op", i, i + 2); i = i + 3
      elseif two == "//" or two == "==" or two == "~=" or two == "<=" or two == ">=" or two == ".." or two == "::" or two == "<<" or two == ">>" then push("op", i, i + 1); i = i + 2
      else push("op", i, i); i = i + 1 end
    end
  end
  return out
end

local KEYWORDS = {}
for w in ("and break do else elseif end false for function goto if in local nil not or repeat return then true until while"):gmatch("%a+") do KEYWORDS[w] = true end
local VALUE_END = { ["true"] = true, ["false"] = true, ["nil"] = true, ["end"] = false }
local CLOSE = { [")"] = "(", ["]"] = "[", ["}"] = "{" }
local OPEN = { ["("] = ")", ["["] = "]", ["{"] = "}" }

-- Does token k end a value (so a following `-` is binary, a following `(` a call)?
local function ends_value(t)
  if not t then return false end
  if t.kind == "num" or t.kind == "str" then return true end
  if t.kind == "name" then return not KEYWORDS[t.text] or VALUE_END[t.text] == true end
  return t.text == ")" or t.text == "]" or t.text == "}" or t.text == "..."
end

-- Start of the primary expression (with suffixes) that ends at token k.
local function primary_start(t, k)
  while k >= 1 do
    local tk = t[k]
    if CLOSE[tk.text] then
      local depth = 0
      repeat
        if CLOSE[t[k].text] then depth = depth + 1 elseif OPEN[t[k].text] then depth = depth - 1 end
        if depth > 0 then k = k - 1 end
      until depth == 0 or k < 1
      -- t[k] is the opening bracket: a call or index if a value precedes it
      if ends_value(t[k - 1]) or (t[k - 1] and t[k - 1].kind == "str") then k = k - 1 else return k end
    elseif tk.kind == "str" and t[k - 1] and (ends_value(t[k - 1]) and t[k - 1].kind ~= "num") and t[k - 1].kind ~= "str" then
      k = k - 1  -- f"str" call
    elseif tk.kind == "name" or tk.kind == "num" or tk.kind == "str" or tk.text == "..." then
      local p = t[k - 1]
      if p and (p.text == "." or p.text == ":") then k = k - 2 else return k end
    else
      return k
    end
  end
  return 1
end

local function is_unary(t, k)
  local x = t[k]
  if not x then return false end
  if x.text == "not" or x.text == "#" then return true end
  if x.text == "-" or x.text == "~" then return not ends_value(t[k - 1]) end
  return false
end

-- Start of the term ending at token k: a primary, a ^ chain, unary operators before it.
local function term_start(t, k)
  local s = primary_start(t, k)
  while t[s - 1] and t[s - 1].text == "^" do s = primary_start(t, s - 2) end
  while is_unary(t, s - 1) do s = s - 1 end
  return s
end

-- End of the term starting at token k.
local function term_end(t, k)
  while t[k] and (t[k].text == "not" or t[k].text == "#" or t[k].text == "-" or t[k].text == "~") do k = k + 1 end
  local function primary_end(k)
    local x = t[k]
    if not x then return k end
    if OPEN[x.text] then
      local depth = 0
      repeat
        if OPEN[t[k].text] then depth = depth + 1 elseif CLOSE[t[k].text] then depth = depth - 1 end
        k = k + 1
      until depth == 0 or not t[k]
      k = k - 1
    end
    -- suffixes: .name :name(args) [index] (args) "str" {table}
    while t[k + 1] do
      local nx = t[k + 1].text
      if (nx == "." or nx == ":") and t[k + 2] and t[k + 2].kind == "name" then k = k + 2
      elseif nx == "(" or nx == "[" or nx == "{" then
        local depth = 0
        k = k + 1
        repeat
          if OPEN[t[k].text] then depth = depth + 1 elseif CLOSE[t[k].text] then depth = depth - 1 end
          k = k + 1
        until depth == 0 or not t[k]
        k = k - 1
      elseif t[k + 1].kind == "str" then k = k + 1
      else break end
    end
    return k
  end
  local e = primary_end(k)
  while t[e + 1] and t[e + 1].text == "^" do e = term_end(t, e + 2) end
  return e
end

--- Lua 5.3 source as LuaJIT can load it: floor division rewritten. Returns the source unchanged when it has none.
local function lua53(src)
  if not src:find("//", 1, true) then return src end
  for _ = 1, 10000 do
    local t = tokens(src)
    local k
    for i, x in ipairs(t) do if x.text == "//" and x.kind == "op" then k = i; break end end
    if not k then return src end
    local ls = term_start(t, k - 1)
    while t[ls - 1] and (t[ls - 1].text == "*" or t[ls - 1].text == "/" or t[ls - 1].text == "%") do ls = term_start(t, ls - 2) end
    local re = term_end(t, k + 1)
    local left = src:sub(t[ls].a, t[k - 1].b)
    local right = src:sub(t[k + 1].a, t[re].b)
    src = src:sub(1, t[ls].a - 1) .. "math.floor((" .. left .. ") / (" .. right .. "))" .. src:sub(t[re].b + 1)
  end
  return src
end
M._lua53 = lua53

-- Load a file of norns Lua, translated; its chunk is named by its path, so errors name the file.
local function loadfile53(path)
  local f, err = io.open(path, "r")
  if not f then return nil, err end
  local src = f:read("*a"); f:close()
  return loadstring(lua53(src), "@" .. path)
end
local function dofile53(path)
  local chunk, err = loadfile53(path)
  if not chunk then error(err, 2) end
  return chunk()
end
M.dofile = dofile53

-- The Lua 5.3 library, as far as scripts use it.
table.unpack = table.unpack or unpack
table.pack = table.pack or function(...) return { n = select('#', ...), ... } end
table.move = table.move or function(a, f, e, t, b)
  b = b or a
  if e >= f then
    if t > f then for i = e - f, 0, -1 do b[t + i] = a[f + i] end else for i = 0, e - f do b[t + i] = a[f + i] end end
  end
  return b
end
math.tointeger = math.tointeger or function(x) x = tonumber(x); if x and x == floor(x) then return x end end
math.type = math.type or function(x) if type(x) ~= "number" then return nil end; return x == floor(x) and "integer" or "float" end
math.maxinteger, math.mininteger = math.maxinteger or 2 ^ 53, math.mininteger or -2 ^ 53
math.ult = math.ult or function(a, b) return a < b end
utf8 = utf8 or { char = function(...) local o = {}; for i, c in ipairs { ... } do o[i] = c < 128 and string.char(c) or "?" end; return table.concat(o) end,
                 charpattern = "[\0-\x7F\xC2-\xF4][\x80-\xBF]*", len = function(s) return #s end }
-- require finds the script's own modules through the same translation.
table.insert(package.loaders, 2, function(name)
  local p = package.searchpath and package.searchpath(name, package.path)
  if not p then
    for pat in package.path:gmatch("[^;]+") do
      local c = pat:gsub("%?", (name:gsub("%.", "/")))
      if util.file_exists(c) then p = c; break end
    end
  end
  if not p then return nil end
  local chunk, err = loadfile53(p)
  if not chunk then error(err, 0) end
  return chunk
end)

--- include("lib/foo"): run a file of the script's, from the script's folder or from _path.code.
function include(name)
  for _, base in ipairs { M.state.path, _path.code } do
    local p = base .. name .. ".lua"
    if util.file_exists(p) then return dofile53(p) end
  end
  error("include: no " .. name .. ".lua beside the script or under " .. _path.code, 2)
end

package.preload["musicutil"] = function() return MU end
package.preload["util"] = function() return util end
package.preload["controlspec"] = function() return controlspec end
package.preload["lib/musicutil"] = package.preload["musicutil"]

--- norns.reset(): fresh norns state — params, clocks, metros, screen ops — for a script about to load.
function M.reset()
  for id in pairs(H.tasks) do H.tasks[id] = nil end
  for id in pairs(H.metros) do H.metros[id] = nil end
  H.now, H.tempo, H.beat_t, H.beat_b, H.mode = 0, 120, 0, 0, nil
  ops = {}
  devices.grids, devices.arcs = {}, {}
  grid.vports, arc.vports, midi.vports = {}, {}, {}
  clock.transport = {}
  engine.name = nil
  params = new_params()
  params:add_number("clock_tempo", "tempo", 1, 300, 120)
  params:set_action("clock_tempo", set_tempo)
  params:hide("clock_tempo")
  for _, name in ipairs { "init", "redraw", "key", "enc", "cleanup" } do rawset(_G, name, nil) end
  return M
end

--- norns.load(path) or norns.load{code=, name=}: reset, then run a norns script — a file, as norns
--- does, its folder its home; or a string of Lua, such as another box's code. A norns.start() inside
--- the script being loaded does nothing, so a box that starts itself can also be rendered.
function M.load(path, options)
  options = options or {}
  if type(path) == "table" then options, path = path, nil end
  M.reset()
  if not path then
    M.state.name = options.name or "script"
    M.state.shortname, M.state.path = M.state.name, _path.code .. M.state.name .. "/"
    local chunk, err = loadstring(lua53(assert(options.code, "norns.load needs a path or code=")), "=" .. M.state.name)
    if not chunk then error(err, 2) end
    H.loading = true
    local ok, e = pcall(chunk)
    H.loading = false
    if not ok then error(e, 0) end
    return M
  end
  M.state.path = dirname(path)
  M.state.name = path:match("([^/]+)%.lua$") or path
  M.state.shortname = M.state.name
  _path.code = options.code or dirname(M.state.path:sub(1, -2))
  M.state.data = _path.data .. M.state.name .. "/"
  M.state.lib = M.state.path .. "lib/"
  package.path = M.state.path .. "?.lua;" .. M.state.path .. "lib/?.lua;" .. _path.code .. "?.lua;" .. package.path
  dofile53(path)
  return M
end

local function lifecycle_start()
  if rawget(engine, "name") and not ENGINES[engine.name] then
    print("norns: Field has no engine '" .. tostring(engine.name) .. "'; its commands are silent")
  end
  if type(rawget(_G, "init")) == "function" then init() end
  if type(rawget(_G, "redraw")) == "function" then redraw() end
end

--- norns.key(n, z): press (z = 1) or release (z = 0) key n, as the script's key(n, z) sees it.
function M.key(n, z) local f = rawget(_G, "key"); if f then f(n, z) end end
--- norns.enc(n, d): turn encoder n by d.
function M.enc(n, d) local f = rawget(_G, "enc"); if f then f(n, d) end end
--- norns.grid_key(x, y, z, n): press a key of the virtual grid.
function M.grid_key(x, y, z, n) local g = devices.grids[n or 1]; if g and g.key then g.key(x, y, z) end end
--- norns.arc_delta(ring, d, n): turn a ring of the virtual arc.
function M.arc_delta(ring, d, n) local a = devices.arcs[n or 1]; if a and a.delta then a.delta(ring, d) end end

local function drive(name, down)
  local kind, n, sign = name:match("^(%a)(%d)([%+%-]?)$")
  n = tonumber(n)
  if kind == "k" then M.key(n, down and 1 or 0)
  elseif kind == "e" and down then M.enc(n, sign == "-" and -1 or 1) end
end

--- norns.start(): start the script live — init, redraw, then its clocks and metros every frame. Launch the box.
function M.start(options)
  if H.loading then return M end
  options = options or {}
  screen_opts = { show = true, scale = options.scale or 4 }
  raw.begin("live")
  H.mode, H.now = "live", 0
  if options.controls ~= false then raw.controls(drive) end
  local t0 = field.clock()
  lifecycle_start()
  spawn(function()
    while H.mode == "live" do
      step(field.clock() - t0)
      wait()
    end
  end)
  return M
end

--- norns.run(path): load a norns script file and start it live. Launch the box.
function M.run(path, options) M.load(path, options); return M.start(options) end

--- norns.stop(): call the script's cleanup and stop its clocks and metros.
function M.stop()
  local f = rawget(_G, "cleanup")
  H.mode = nil
  if f then f() end
  for id in pairs(H.tasks) do H.tasks[id] = nil end
  for _, m in pairs(H.metros) do m.running = false end
end

--- norns.render{seconds=, script=, events=, screens=, wav=, png=, show=}: run the script offline in
--- virtual time and render its sound. `events` is a list of {t, "key", n, z}, {t, "enc", n, d},
--- {t, "grid", x, y, z}, {t, "arc", ring, d} or {t, fn}; `screens` a list of {t, path} PNGs to write.
--- Returns what the sound measured: frames, seconds, peak, rms and onsets.
function M.render(o)
  o = o or {}
  if o.script then M.load(o.script, o) elseif o.code then M.load { code = o.code, name = o.name } end
  local seconds = tonumber(o.seconds) or 4
  screen_opts = { show = false }
  raw.begin("offline")
  H.mode, H.now = "offline", 0
  lifecycle_start()
  local events = {}
  for _, e in ipairs(o.events or {}) do events[#events + 1] = e end
  for _, s in ipairs(o.screens or {}) do events[#events + 1] = { s[1], "screen", s[2] } end
  table.sort(events, function(a, b) return a[1] < b[1] end)
  local function advance(t) raw.advance(t) end
  for _, e in ipairs(events) do
    local t = min(e[1], seconds)
    step(t, advance)
    advance(t)
    local kind = e[2]
    if kind == "key" then M.key(e[3], e[4])
    elseif kind == "enc" then M.enc(e[3], e[4])
    elseif kind == "grid" then M.grid_key(e[3], e[4], e[5])
    elseif kind == "arc" then M.arc_delta(e[3], e[4])
    elseif kind == "screen" then screen_opts.png = e[3]; screen.update(); screen_opts.png = nil
    elseif type(kind) == "function" then kind() end
  end
  step(seconds, advance)
  advance(seconds)
  local f = rawget(_G, "cleanup")
  H.mode = nil
  for id in pairs(H.tasks) do H.tasks[id] = nil end
  for _, m in pairs(H.metros) do m.running = false end
  screen_opts = { show = o.show ~= false, scale = o.scale or 4, png = o.png }
  screen.update()
  screen_opts.png = nil
  if f and o.cleanup ~= false then pcall(f) end
  return raw.finish { wav = o.wav, show = o.show, label = o.label or (M.state.name ~= "" and M.state.name or "norns") }
end

--- norns.screen_png(path): write the screen as it stands to a PNG, 4x.
function M.screen_png(path) local s = screen_opts; screen_opts = { show = false, png = path }; screen.update(); screen_opts = s end

--- norns.coverage(): which parts of the norns API this run used that Field only stubs, or does not have.
function M.coverage()
  local stubs, missing = {}, {}
  for k in pairs(used_stubs) do stubs[#stubs + 1] = k end
  for k in pairs(unhandled) do missing[#missing + 1] = k end
  for _, k in ipairs(raw.unhandled() or {}) do missing[#missing + 1] = k end
  table.sort(stubs); table.sort(missing)
  return { stubbed = stubs, missing = missing }
end

norns = M
M.reset()
return M
