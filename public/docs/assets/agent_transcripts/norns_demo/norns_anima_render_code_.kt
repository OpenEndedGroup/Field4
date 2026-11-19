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
