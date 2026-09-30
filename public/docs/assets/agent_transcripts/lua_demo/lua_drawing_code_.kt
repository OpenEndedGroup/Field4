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
