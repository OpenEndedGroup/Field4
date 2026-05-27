[[vk::binding(0, 0)]]
StructuredBuffer<float4> foo;

struct Matrices
{
    float4x4 p;
    float4x4 m;
    float4* vertexData;
    float4 d;
};

struct Varyings
{
    float4 position : SV_Position;
    float4 localColor : TEXCOORD0;
};

float wrap(int a, int b)
{
    return float(a % b) / float(b - 1);
}

[shader("vertex")]
Varyings vsMain(uint vertexIndex : SV_VulkanVertexID, uniform Matrices matrices)
{
    Varyings output;

    output.position = float4(matrices.vertexData[vertexIndex].xyz, 1.0);
    output.localColor = float4(
        wrap(int(vertexIndex), 3),
        wrap(int(vertexIndex), 5),
        wrap(int(vertexIndex), 11),
        1.0);

    output.localColor.r += foo[0].x;
    return output;
}

[shader("fragment")]
float4 fsMain(Varyings input) : SV_Target0
{
    return float4(input.localColor.rgb, 1.0);
}