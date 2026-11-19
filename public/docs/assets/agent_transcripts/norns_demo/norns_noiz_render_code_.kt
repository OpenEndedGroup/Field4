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
