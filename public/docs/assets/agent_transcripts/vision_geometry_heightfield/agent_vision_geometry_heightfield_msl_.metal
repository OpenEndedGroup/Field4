vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float3 centre = float3(0.0, 0.0, -1.2);
    float3 p = v.position.xyz - centre;
    float3 n = v.normal.xyz;

    // Tip it towards the viewer so the relief reads, then turn it slowly.
    float tilt = -0.55;
    float ct = cos(tilt), st = sin(tilt);
    p.yz = float2(ct * p.y - st * p.z, st * p.y + ct * p.z);
    n.yz = float2(ct * n.y - st * n.z, st * n.y + ct * n.z);

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
    float3 n = normalize(in.normal);

    // Lit from both sides, since a height field is a sheet you can see the back of.
    float diffuse = 0.22 + 0.78 * abs(dot(n, light));
    float rim = pow(1.0 - abs(n.z), 2.0) * 0.15;

    return half4(half3(in.color.rgb * diffuse + rim), 1.0h);
}
