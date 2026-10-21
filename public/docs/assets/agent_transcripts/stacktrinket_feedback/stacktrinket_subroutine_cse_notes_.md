# Subroutine cse

`: ring-a … ;` defines a word; using its name runs it. Two remembered rings, with ring-a used twice.
The compiler inlines definitions and computes ring-a once (look in the glsl panel). The page's
rings push a value without taking one, which Field refuses, so each starts with `drop`.
