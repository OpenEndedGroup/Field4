#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_debug_printf : enable

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
    vec3 pos = pc.vertexData.positions[gl_VertexIndex].xyz;
    gl_Position = vec4(pos, 1);

    localColor = vec4(wrap(gl_VertexIndex,3),wrap(gl_VertexIndex,5),wrap(gl_VertexIndex,11),1.0);

    if (gl_VertexIndex == 10) {
        debugPrintfEXT("VS: index=%d, pos=(%f,%f)",gl_VertexIndex, pos.x, pos.y);
    }
}