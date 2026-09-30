-- Launch this box: spawn runs a function as a coroutine, wait(s) pauses it.
spawn(function()
  for i = 1, 4 do
    print("tick", i, "frame", field.frame())
    wait(0.5)
  end
  print("done")
end)
