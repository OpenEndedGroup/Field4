// Spins the whole mesh about the group centre, then shades it. The plane picks up a
// checker from the uv its builder wrote; everything else has uv (0,0)..(1,1) per face.
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float3 centre = float3(0.0, 0.0, -1.2);
    float3 p = v.position.xyz - centre;
    float3 n = v.normal.xyz;

    float a = u_spin.x;
    float c = cos(a), s = sin(a);
    p.xz = float2(c * p.x + s * p.z, -s * p.x + c * p.z);
    n.xz = float2(c * n.x + s * n.z, -s * n.x + c * n.z);

    float4 world = frame.modelMatrix * float4(p + centre, 1.0);
    FieldVarying out;
    out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;
    out.worldPosition = world.xyz;
    out.normal = normalize(n);
    out.color = v.color;
    out.uv = v.uv.xy;
    return out;
}

fragment half4 field_fragment(
    FieldVarying in [[stage_in]],
    constant FieldFrame &frame [[buffer(1)]])
{
    float3 light = normalize(u_light.xyz);
    float diffuse = 0.25 + 0.75 * max(dot(normalize(in.normal), light), 0.0);

    // An 8x8 checker straight off the interpolated uv.
    float2 cell = floor(in.uv * 8.0);
    float checker = fmod(cell.x + cell.y, 2.0);
    float3 rgb = in.color.rgb * mix(0.72, 1.0, checker) * diffuse;

    return half4(half3(rgb), half(in.color.a));
}
