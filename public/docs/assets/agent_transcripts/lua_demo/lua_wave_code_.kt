-- Launch this box: update(dt) runs every frame until you stop it.
local t = 0
function update(dt)
  t = t + dt
  local l = line()
  for i = 0, 100 do
    local x, y = i * 4, 100 + 60 * math.sin(i / 8 + t * 3)
    if i == 0 then l:moveTo(x, y) else l:lineTo(x, y) end
  end
  lines(l:color(0.3, 0.8, 1):width(2), {w = 400, h = 200})
end
