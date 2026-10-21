ShaderTrinket, in Field. Launch the renderer above, then this box: the window shows Flat Rings,
ShaderTrinket's default program.

The program is this box's fragment property, a ShaderToy Image pass: you write
void mainImage(out vec4 fragColor, in vec2 fragCoord), where fragCoord is the pixel (y up, as in
ShaderToy) and fragColor the colour you want seen there. The ShaderToy uniforms are all bound:
iResolution (the canvas size), iTime, iTimeDelta, iFrame, iFrameRate, iDate, iMouse (the window
cursor in canvas pixels, zw the click while the button is down) and iChannel0..3 with
iChannelResolution. The {{ shadertoy.image }} line at the top brings them in; leave it there.

Edit the fragment and press alt-R to recompile. A mistake is marked on its own line and the last
good program keeps running. ShaderToy code pasted from the web works as is.

Every shader box here draws into one canvas, shadertoyCanvas(), so launching a box replaces the
picture, the way ShaderTrinket's preset menu does; launch this one again to come back. To read a
texture, add textures("iChannel0" to t) inside the shadertoyHere block: the camera (see the
Camera Input Demo), another canvas (shadertoyCanvas(name = "...", inWindow = false).rtt), or any
Field texture. Channels come out the right way up and in ShaderToy's colours.

Ported from aaaframe's madp/shadertrinket.html; dev_docs/madp/shadertrinket.md has the mapping.
