// The Slang source for this lesson: the vertex stage (`vsMain`) and fragment stage (`fsMain`) in one file.
// Field compiles it once per stage with `slangc`.

// An entry point's `uniform` parameter becomes the push-constant block. `vertexData` is a pointer to the
// buffer the Kotlin side assigns with `m.vertexData = { vertexData }`.
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

// a ramp from 0 to 1 that repeats every b vertices, used to give each vertex its own colour
float wrap(int a, int b)
{
    return float(a % b) / float(b - 1);
}

[shader("vertex")]
Varyings vsMain(uint vertexIndex : SV_VulkanVertexID, uniform Matrices matrices)
{
    Varyings output;

    // positions are already in clip space, so no camera transform is applied
    output.position = float4(matrices.vertexData[vertexIndex].xyz, 1.0);
    output.localColor = float4(
        wrap(int(vertexIndex), 3),
        wrap(int(vertexIndex), 5),
        wrap(int(vertexIndex), 11),
        1.0);

    return output;
}

[shader("fragment")]
float4 fsMain(Varyings input) : SV_Target0
{
    return float4(input.localColor.rgb, 1.0);
}