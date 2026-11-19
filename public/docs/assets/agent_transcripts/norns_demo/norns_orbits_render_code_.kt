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
