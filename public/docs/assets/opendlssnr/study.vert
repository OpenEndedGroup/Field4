#version 460
layout(location=0) out vec2 uv;
void main() {
    // The 0/2 corners make an oversized fullscreen triangle from three vertices.
    vec2 corner=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    // Flip Y when converting raster corners to source texture coordinates.
    uv=vec2(corner.x,1-corner.y);
    gl_Position=vec4(corner*2-1,0,1);
}
