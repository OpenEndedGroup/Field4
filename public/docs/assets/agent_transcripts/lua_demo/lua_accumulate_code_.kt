-- One Lua state per box, kept across runs. Select line 2 and run it, then line 3.
function greet(who) return "hello, " .. who end
greet("Field")
