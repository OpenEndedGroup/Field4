-- box is this box; its properties read and write by name. Run it twice.
box.counter = (box.counter or 0) + 1
print("run", box.counter, "of", tostring(box))
for _, parent in ipairs(box.parents) do print("a parent:", parent.name, "with", #parent.children, "children") end
print("the drawing box is at x =", field.named("drawing").frame.x)
