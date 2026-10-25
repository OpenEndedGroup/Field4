/*
StackSL is a stack language in which every value is a vec4. A program starts with
input = vec4(uv, hash, time) on the stack; each word takes values off the top and
pushes its result, and whatever is on top at the end is the colour of the pixel.

This box's program is in its [[property stackSL]] tab. `p` is uv centred on the
middle, `length` its distance from there, `0.5 0.08 pulse` a soft band at radius 0.5,
and `white *` colours it.

stackFeedback() draws the program over the whole window every frame, with the frame
before bound as `state`: `prev` reads it at this pixel, `0.004 0 vec2 sample` a little
to the side. Edit the program while it runs and the new one takes over without
clearing the picture; run this box again to start from black. The lowering panel
shows how deep the stack ends (here 2: the input is still under the ring) and any
error at its line and column; open the glsl panel for the GLSL the program becomes.
*/
stackFeedback()
