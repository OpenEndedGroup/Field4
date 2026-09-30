-- Lua makes the notes, Field's pattern voice plays them (the norns shape). Run it to hear the
-- sequence loop; run it again for new notes, stop the box for silence.
local scale = {0, 2, 3, 5, 7, 8, 10}
local notes = {}
for i = 1, 8 do notes[i] = 57 + scale[math.random(#scale)] + (i % 4 == 0 and 12 or 0) end
print("notes", table.concat(notes, " "))
local heard = field.render(notes, {cps = 0.5, wave = "triangle", decay = 0.3})
print(string.format("%.0f s, peak %.2f, rms %.3f", heard.seconds, heard.peak, heard.rms))
play(notes, {cps = 0.5, wave = "triangle", decay = 0.3})
