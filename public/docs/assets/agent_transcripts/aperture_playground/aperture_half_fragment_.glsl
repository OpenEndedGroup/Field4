#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(set=0,binding=0) uniform sampler2D paintSource;
void main() {
    // Average a pixel footprint. RGBA remain independent energy bands.
    vec2 d = 0.25 * vec2(dFdx(uv.x), dFdy(uv.y));
    result = 0.25 * (texture(paintSource,uv+vec2(-d.x,-d.y))
        + texture(paintSource,uv+vec2(d.x,-d.y))
        + texture(paintSource,uv+vec2(-d.x,d.y)) + texture(paintSource,uv+d));
}
