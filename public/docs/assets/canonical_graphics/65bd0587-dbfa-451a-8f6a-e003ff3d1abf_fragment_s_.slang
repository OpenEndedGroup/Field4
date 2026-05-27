struct FragmentInput
{
    [[vk::location(0)]]
    float4 localColor;
};

[shader("fragment")]
float4 main(FragmentInput input) : SV_Target0
{
    return float4(input.localColor.rgb-float3(1,1,1)*0, 1);
}