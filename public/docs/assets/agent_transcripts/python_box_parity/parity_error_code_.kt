# The marker lands on line 6, inside mean(), where the division failed --
# not on line 1, and not on the call on line 8.
readings = []

def mean(values):
    return sum(values) / len(values)

mean(readings)
