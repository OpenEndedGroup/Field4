/*
Definitions: `: ring-a … ;` names a piece of program, and using the name runs it.
ring-a and ring-b are two remembered rings; ring-a is called twice. The compiler
inlines definitions and notices the repeat: in the lowered GLSL in the glsl panel,
ring-a is computed once and its value used twice. The average of the three goes back
into red, and the palette colours it.

A definition must leave the stack as deep as it found it: each ring is called with
`input` on top, so it starts with `drop` and ends with its one ring in its place.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
