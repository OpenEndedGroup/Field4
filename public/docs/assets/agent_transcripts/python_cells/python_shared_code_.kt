# There is one interpreter for the whole Field process, and one global namespace.
# `a` was never defined here -- it is still bound from cell 1. Run cell 1 first.
print("a is still here:", a.shape, a.sum())

# Which also means state survives between runs of this cell.
counter = globals().get("counter", 0) + 1
print("this cell has run", counter, "time(s)")
