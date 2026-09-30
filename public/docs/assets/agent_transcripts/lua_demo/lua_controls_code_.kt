-- A slider made from Lua: move it and the circle follows. slider() returns its first value.
local function draw(r)
  local frame = line():rect(0, 0, 300, 200):color(0.5, 0.5, 0.5):width(1)
  lines(frame, line():circle(150, 100, r):color(1, 0.5, 0.2):fill(), {w = 300, h = 200})
end
draw(slider("radius", 5, 90, 40, draw))
