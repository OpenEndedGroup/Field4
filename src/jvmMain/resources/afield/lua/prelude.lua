-- Field's Lua prelude: the `field` API a Lua box sees, written in Lua over the raw Kotlin handlers.
--
-- Loaded once into every lua_State by afield.lua.LuaState. The raw handlers arrive in the global
-- __field_raw, which this chunk takes and removes. Every raw handler returns `true, results...` or
-- `false, message`; it never raises, because a Lua error must not unwind through the Java frames of an
-- FFM upcall. `checked` turns the `false` into an ordinary Lua error at the caller's line.
--
-- Names starting __field_ are called from Kotlin and are not API.

local raw = __field_raw or {}
__field_raw = nil

local type, tostring, tonumber, select, error, pairs, ipairs = type, tostring, tonumber, select, error, pairs, ipairs
local setmetatable, getmetatable, rawget, unpack = setmetatable, getmetatable, rawget, unpack
local concat, floor, abs, format = table.concat, math.floor, math.abs, string.format
local getinfo, getlocal, sethook = debug.getinfo, debug.getlocal, debug.sethook

local function pack(...) return { n = select('#', ...), ... } end

-- Wrap a raw handler so that its failure is a Lua error raised where the box called it.
local function checked(f)
  return function(...)
    local r = pack(f(...))
    if r[1] ~= true then error(tostring(r[2] or "a call into Field failed"), 2) end
    return unpack(r, 2, r.n)
  end
end
__field_checked = checked

field = { version = jit and jit.version or _VERSION, budget = 5, frameBudget = 1 }

-- The JIT compiler starts OFF. A loop LuaJIT has compiled to machine code never calls a debug hook, so
-- the guard below cannot stop `while true do end` once it is hot, and Field's main thread — where box
-- code runs — would be frozen for good (measured: LuaStateTest.aCompiledLoopEscapesTheCountHook). The
-- interpreter is still LuaJIT's fast one. `jit.on()` in a box turns the compiler on for that box's
-- state, and gives up the guard for whatever it compiles.
if jit then jit.off() end

-- ── values, as a line note or a REPL shows them ─────────────────────────────────────────────────

local describe
describe = function(v, nested)
  local t = type(v)
  if t == "number" then
    if v == floor(v) and abs(v) < 1e15 then return format("%d", v) end
    return format("%.6g", v)
  elseif t == "string" then
    if #v > 48 then v = v:sub(1, 47) .. "…" end
    return '"' .. v:gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n") .. '"'
  elseif t == "boolean" or t == "nil" then
    return tostring(v)
  elseif t == "table" then
    local mt = getmetatable(v)
    if mt and mt.__tostring then return tostring(v) end
    local n, k = #v, 0
    for _ in pairs(v) do k = k + 1; if k > 1000 then break end end
    if k == 0 then return "{}" end
    if nested then return n == k and ("a list of " .. n) or "a table" end
    if n == k and n <= 8 then
      local parts = {}
      for i = 1, n do parts[i] = describe(v[i], true) end
      return "{" .. concat(parts, ", ") .. "}"
    end
    if n == k then return "a list of " .. n end
    return "a table of " .. k .. (k > 1000 and "+" or "") .. " entries"
  elseif t == "function" then
    return "a function"
  end
  return "a " .. t
end
field.describe = describe

-- ── output ──────────────────────────────────────────────────────────────────────────────────────

if raw.print then
  local out = checked(raw.print)
  function print(...)
    local n = select('#', ...)
    local parts = {}
    for i = 1, n do parts[i] = tostring((select(i, ...))) end
    out(concat(parts, "\t"))
  end
end

if raw.display then
  --- display(html) or display(channel, html): HTML into one of the box's output panels.
  display = checked(raw.display)
  field.display = display
end

-- ── boxes ───────────────────────────────────────────────────────────────────────────────────────

local proxies = setmetatable({}, { __mode = "v" })
local proxy

-- What the raw box handlers return is tagged: ("box", id), ("boxes", {ids}) or ("value", v).
local function untag(kind, v)
  if kind == "box" then return proxy(v)
  elseif kind == "boxes" then
    local out = {}
    for i, id in ipairs(v) do out[i] = proxy(id) end
    return out
  end
  return v
end

local box_get = raw.get and checked(raw.get)
local box_set = raw.set and checked(raw.set)

proxy = function(id)
  if id == nil then return nil end
  local p = proxies[id]
  if p then return p end
  -- One metatable per box, carrying the id in `__box`, which is how Kotlin recognises a proxy.
  local mt = { __box = id }
  mt.__index = function(_, k)
    if k == "id" then return id end
    return untag(box_get(id, k))
  end
  mt.__newindex = function(_, k, v) box_set(id, k, v) end
  mt.__tostring = function() return "box '" .. tostring(untag(box_get(id, "name"))) .. "'" end
  p = setmetatable({}, mt)
  proxies[id] = p
  return p
end
__field_proxy = proxy

if raw.self then
  box = proxy(select(2, raw.self()))
  field.box = box
end

if raw.named then
  local named = checked(raw.named)
  --- The box called `name` in this document, or nil.
  function field.named(name) return untag(named(name)) end
end

-- ── lines ───────────────────────────────────────────────────────────────────────────────────────

local Line = {}
Line.__index = Line
Line.__tostring = function(l) return "a line of " .. #l.ops .. " segments" end
field.Line = Line

--- A new line; `line{{x, y}, {x, y}, ...}` starts it as a polyline through those points.
function line(points)
  local l = setmetatable({ ops = {} }, Line)
  if points then
    for i, p in ipairs(points) do
      if i == 1 then l:moveTo(p[1] or p.x, p[2] or p.y) else l:lineTo(p[1] or p.x, p[2] or p.y) end
    end
  end
  return l
end
field.line = line

local function op(l, o) l.ops[#l.ops + 1] = o; return l end
function Line:moveTo(x, y) return op(self, { "m", x, y }) end
function Line:lineTo(x, y) return op(self, { "l", x, y }) end
function Line:cubicTo(ax, ay, bx, by, x, y) return op(self, { "c", ax, ay, bx, by, x, y }) end
function Line:close() return op(self, { "z" }) end
function Line:circle(x, y, r) return op(self, { "o", x, y, r }) end
function Line:rect(x, y, w, h)
  return self:moveTo(x, y):lineTo(x + w, y):lineTo(x + w, y + h):lineTo(x, y + h):close()
end
--- Colour in 0..1, alpha optional.
function Line:color(r, g, b, a) self.rgba = { r, g, b, a or 1 }; return self end
function Line:width(w) self.thickness = w; return self end
function Line:fill(on) self.filled = (on ~= false); return self end
function Line:stroke(on) self.stroked = (on ~= false); return self end

if raw.lines then
  local draw = checked(raw.lines)
  --- lines(l1, l2, ...) or lines({l1, l2, ...}), optionally ending with {channel=, w=, h=, fit=}:
  --- draw lines in the box's output, in the `lines` panel unless a channel is given.
  function lines(...)
    local list, options = {}, nil
    local n = select('#', ...)
    for i = 1, n do
      local a = select(i, ...)
      if getmetatable(a) == Line then list[#list + 1] = a
      elseif type(a) == "table" and getmetatable(a[1]) == Line then
        for _, l in ipairs(a) do list[#list + 1] = l end
      elseif type(a) == "table" and i == n then options = a
      elseif a ~= nil then error("lines() takes lines made with line(), and an options table last; got " .. describe(a), 2)
      end
    end
    return draw(list, options or {})
  end
  field.lines = lines
end

-- ── time: update(dt), spawn and wait ────────────────────────────────────────────────────────────

local clock = raw.clock and checked(raw.clock) or os.clock
field.clock = clock
if raw.frame then field.frame = checked(raw.frame) end

local tasks = {}

--- Run fn as a coroutine, resumed once a frame while the box is launched; `wait(s)` inside it
--- pauses it for s seconds (or one frame).
function spawn(fn, ...)
  if type(fn) ~= "function" then error("spawn(fn, ...) needs a function", 2) end
  local co = coroutine.create(fn)
  tasks[#tasks + 1] = { co = co, wake = 0, args = pack(...) }
  return co
end
field.spawn = spawn

--- Inside a spawned function: pause for `seconds` (one frame when omitted). Returns the time.
function wait(seconds)
  if not coroutine.running() then error("wait() only works inside a function started with spawn()", 2) end
  return coroutine.yield(tonumber(seconds) or 0)
end
field.wait = wait

function __field_pending()
  return #tasks, type(rawget(_G, "update")) == "function"
end

--- One frame of a launched box: `update(dt)`, then every task that is due. False when nothing is left.
function __field_tick(now, dt)
  local update = rawget(_G, "update")
  if type(update) == "function" then update(dt) end
  local i = 1
  while i <= #tasks do
    local t = tasks[i]
    if now >= t.wake then
      local ok, s
      if t.args then
        local a = t.args
        t.args = nil
        ok, s = coroutine.resume(t.co, unpack(a, 1, a.n))
      else
        ok, s = coroutine.resume(t.co, now)
      end
      if not ok then
        table.remove(tasks, i)
        error(tostring(s), 0)
      elseif coroutine.status(t.co) == "dead" then
        table.remove(tasks, i)
      else
        t.wake = now + (tonumber(s) or 0)
        i = i + 1
      end
    else
      i = i + 1
    end
  end
  return type(rawget(_G, "update")) == "function" or #tasks > 0
end

function __field_halt() tasks = {} end

-- ── the guard and the recorder ──────────────────────────────────────────────────────────────────
--
-- A count hook checks a deadline every COUNT instructions, so `while true do end` stops with an error
-- instead of freezing Field's main thread. While a whole run records its line notes, the same hook
-- also takes line and return events: for each line of the main chunk it keeps how often it ran and,
-- once the line has finished, the values of the names it assigned. Events are capped, so a loop at the
-- top level costs a bounded amount and then runs unobserved.

local COUNT, CAP = 100000, 50000
local deadline, budget, rec, events, mainfn, prevline, names, truncated = 0, 5, nil, 0, nil, nil, nil, false
local lastline = setmetatable({}, { __mode = "k" })
local SOURCE = "=box"

local hook

local function capture(line)
  local ns = names and names[line]
  if not ns then return end
  local out = {}
  for j, n in ipairs(ns) do
    local found, val, i = false, nil, 1
    while true do
      local k, v = getlocal(3, i)
      if not k then break end
      if k == n then found, val = true, v end
      i = i + 1
    end
    if not found then val = rawget(_G, n) end
    out[j] = { n, describe(val) }
  end
  rec[line].values = out
end

hook = function(ev, line)
  if ev == "count" then
    if clock() > deadline then
      sethook()
      error(format("ran for more than %g s without finishing, so it was stopped (a loop that never ends?)", budget), 2)
    end
    return
  end
  if not rec then return end
  local info = getinfo(2, "Sf")
  if info.source ~= SOURCE then return end
  events = events + 1
  if events > CAP then
    truncated = true
    sethook(hook, "", COUNT)
    return
  end
  if ev == "line" then
    local fn = info.func
    local main = fn == mainfn
    local r = rec[line]
    if not r then r = { count = 0, main = main }; rec[line] = r end
    -- Lua also reports a line when a call returns into it, so a line is counted only when its own
    -- function moves onto it: a loop body of two lines or more counts its turns exactly, and a loop on
    -- one line counts once.
    if lastline[fn] ~= line then r.count = r.count + 1 end
    lastline[fn] = line
    if main then
      if prevline then capture(prevline) end
      prevline = line
    end
  elseif ev == "return" and info.func == mainfn and prevline then
    capture(prevline)
    prevline = nil
  end
end

--- Arm the guard for one run of fn (seconds from field.budget, or field.frameBudget for a frame) and,
--- when `lineNames` is a table, record lines as well.
function __field_begin(fn, lineNames, perFrame)
  budget = tonumber(perFrame and field.frameBudget or field.budget) or 5
  deadline = clock() + budget
  mainfn, names, prevline, events, truncated = fn, lineNames, nil, 0, false
  lastline = setmetatable({}, { __mode = "k" })
  if lineNames then
    rec = {}
    sethook(hook, "lr", COUNT)
  else
    rec = nil
    sethook(hook, "", COUNT)
  end
end

--- Disarm; returns what was recorded (line -> {count, main, values}) and whether recording was cut off.
function __field_end()
  sethook()
  local r = rec
  rec, mainfn, names = nil, nil, nil
  return r, truncated
end

-- ── errors ──────────────────────────────────────────────────────────────────────────────────────

--- The message handler every call from Kotlin runs under: the message, and the innermost line of box
--- code on the stack when it failed (the only moment that line is knowable).
function __field_msgh(e)
  local msg = type(e) == "string" and e or ("error object: " .. describe(e))
  local line
  for level = 2, 60 do
    local info = getinfo(level, "Sl")
    if not info then break end
    if info.source == SOURCE and info.currentline and info.currentline > 0 then
      line = info.currentline
      break
    end
  end
  return { msg = msg, line = line, trace = debug.traceback("", 2) }
end

-- ── completion ──────────────────────────────────────────────────────────────────────────────────

--- Names reachable at `path` ("" for globals, "string", "field.Line", ...): {name, type} pairs.
--- Box proxies answer {"__box", id}, so Kotlin can list the box's properties.
function __field_complete(path)
  local t = _G
  if path ~= "" then
    for part in path:gmatch("[^%.:]+") do
      if type(t) ~= "table" then t = nil; break end
      local mt = getmetatable(t)
      if mt and mt.__box then return { { "__box", mt.__box } } end
      t = rawget(t, part)
    end
  end
  local out, seen = {}, {}
  local function add(tbl)
    if type(tbl) ~= "table" then return end
    for k, v in pairs(tbl) do
      if type(k) == "string" and not seen[k] and not k:find("^__") then
        seen[k] = true
        out[#out + 1] = { k, type(v) }
      end
    end
  end
  if type(t) == "string" then add(string)
  elseif type(t) == "table" then
    local mt = getmetatable(t)
    if mt and mt.__box then return { { "__box", mt.__box } } end
    add(t)
    if mt and type(mt.__index) == "table" then add(mt.__index) end
  end
  return out
end
