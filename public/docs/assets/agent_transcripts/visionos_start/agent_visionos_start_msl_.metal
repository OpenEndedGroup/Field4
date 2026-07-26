// Uniforms are read by name: the head generates `#define u_phase frame.user[0]`
// and so on from the `uniforms=` list on the marker in the code property.
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float phase = u_phase.x;
    float amount = u_amount.x;
    float3 centre = float3(0.45, 0.0, -1.2);
    float3 p = v.position.xyz
        + v.normal.xyz * sin(phase + v.position.y * 18.0) * amount
        - centre;
    float3 n = v.normal.xyz;

    float angle = phase * 0.18 + 0.55;
    float c = cos(angle), s = sin(angle);
    p.xz = float2(c * p.x + s * p.z, -s * p.x + c * p.z);
    n.xz = float2(c * n.x + s * n.z, -s * n.x + c * n.z);

    float4 world = frame.modelMatrix * float4(p + centre, 1.0);
    FieldVarying out;
    out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;
    out.worldPosition = world.xyz;
    out.normal = n;
    out.color = v.color;
    out.uv = v.uv.xy;
    return out;
}

fragment half4 field_fragment(
    FieldVarying in [[stage_in]],
    constant FieldFrame &frame [[buffer(1)]])
{
    float3 rgb = mix(in.color.rgb, u_tint.rgb, u_tint.a);
    return half4(half3(rgb), half(in.color.a));
}
