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
