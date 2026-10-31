// Vertex (`vsMain`) and fragment (`fsMain`) stages for the texture lesson, in one Slang file.

// vertex attributes: the `pos` and `tc` the box code fills with m.set { ... }
struct VertexInput
{
    [[vk::location(0)]]
    float3 pos;

    [[vk::location(1)]]
    float2 tc;
};

// an entry point's `uniform` parameter becomes the push-constant block (`m.p`, `m.m` in the box code)
struct Matrices
{
    float4x4 p;
    float4x4 m;
};

struct Varyings
{
    float4 position : SV_Position;
    float2 ftc : TEXCOORD0;
};

// bound by name to prop<LoadedTexture>(bbc) in the box code
[[vk::binding(0, 0)]]
Sampler2D bbc;

[shader("vertex")]
Varyings vsMain(VertexInput input, uniform Matrices pc)
{
    Varyings output;
    output.position = mul(pc.p, mul(pc.m, float4(input.pos, 1.0)));
    output.ftc = input.tc;
    return output;
}

[shader("fragment")]
float4 fsMain(Varyings input) : SV_Target0
{
    float4 c = bbc.Sample(input.ftc.yx);
    return float4(c.rgb, 1.0);
}
