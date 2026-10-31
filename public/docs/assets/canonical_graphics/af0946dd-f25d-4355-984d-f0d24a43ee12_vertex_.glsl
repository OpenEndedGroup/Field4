#version 450
#extension GL_EXT_buffer_reference : require

// A buffer reference: a GPU address the box fills in from Kotlin with `m.vertexData = { ... }`
layout(std430, buffer_reference) buffer vertexData
{
    vec4 positions[];
};

// Push constants supplied by the mesh; this shader uses only the buffer address, not the matrices
layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
    vertexData vertexData;
} pc;

layout(location=0) out vec4 localColor;

float wrap(int a, int b) {
    // Divide as floats so the ramp includes intermediate channel values.
    return float(a % b) / float(b - 1);
}

void main()
{
    // look up this vertex's position in the buffer; positions are already in clip space
    gl_Position = vec4(pc.vertexData.positions[gl_VertexIndex].xyz, 1);

    // compute a different color for every vertex
    localColor = vec4(wrap(gl_VertexIndex,3),wrap(gl_VertexIndex,5),wrap(gl_VertexIndex,11),1.0);
}
