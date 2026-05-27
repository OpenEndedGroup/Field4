#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer vertexData
{
    vec4 positions[];
};


layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
    vertexData vertexData;
} pc;

layout(location=0) out vec4 localColor;

float wrap(int a, int b) {
    return (a%b)/(b-1);
}

void main()
{
    gl_Position = vec4(pc.vertexData.positions[gl_VertexIndex].xyz, 1);

    // compute a different color for every vertex
    localColor = vec4(wrap(gl_VertexIndex,3),wrap(gl_VertexIndex,5),wrap(gl_VertexIndex,11),1.0);

//    gl_Position = vec4(gl_VertexIndex%3-1.5 + gl_VertexIndex/20.0, gl_VertexIndex%2-0.5, 0.5, 1);
}