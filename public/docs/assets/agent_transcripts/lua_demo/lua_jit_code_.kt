-- The JIT starts off so the runaway guard works. Turn it on for code you trust to finish.
local function work(n) local s = 0 for i = 1, n do s = s + i % 7 end return s end
local t0 = os.clock(); work(3e7); local interpreted = os.clock() - t0
jit.on()
t0 = os.clock(); work(3e7); local compiled = os.clock() - t0
jit.off()
print(string.format("interpreted %.3f s, compiled %.3f s: %.0fx", interpreted, compiled, interpreted / compiled))
