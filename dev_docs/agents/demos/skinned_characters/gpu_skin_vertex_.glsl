#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require

layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout(push_constant) uniform PushConstants {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats
    uint useVertexColor;   // bool as uint

    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

layout(location = 0) out vec3 vViewPos;
layout(location = 1) out vec3 vNormalVS;
layout(location = 2) out vec2 vUV;
layout(location = 3) out vec4 vColor;

void main()
{
    uint idx = pc.indexBuffer.indexData[pc.indexOffset+uint(gl_VertexIndex)];
    uint base = pc.vertexOffset + idx * pc.vertexStride;

    vec3 pos = vec3(
        pc.vertexBuffer.vertexData[base + 0],
        pc.vertexBuffer.vertexData[base + 1],
        pc.vertexBuffer.vertexData[base + 2]
    );

    vec3 normal = vec3(
        pc.vertexBuffer.vertexData[base + 3],
        pc.vertexBuffer.vertexData[base + 4],
        pc.vertexBuffer.vertexData[base + 5]
    );

    vec2 uv = vec2(
        pc.vertexBuffer.vertexData[base + 6],
        pc.vertexBuffer.vertexData[base + 7]
    );

    vec4 color = vec4(
        pc.vertexBuffer.vertexData[base + 8],
        pc.vertexBuffer.vertexData[base + 9],
        pc.vertexBuffer.vertexData[base + 10],
        pc.vertexBuffer.vertexData[base + 11]
    );

    if (pc.useVertexColor == 0u) {
        color = vec4(1.0);
    }

    vec4 viewPos =  pc.proj * pc.modelView*vec4(pos, 1.0);

    mat3 mv3 = mat3(pc.modelView);
    vec3 normalVS = normalize(mv3 * normal);

    vViewPos = viewPos.xyz;
    vNormalVS = normalVS;
    vUV = uv;
    vColor = color * pc.materialBaseColor;

    gl_Position = viewPos;
}