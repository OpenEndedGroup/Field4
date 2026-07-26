// Note how little this shader does: all the motion is in the geometry, rebuilt in Kotlin.
vertex FieldVarying field_vertex(
    uint vertexID [[vertex_id]], ushort viewID [[amplification_id]],
    const device FieldVertex *vertices [[buffer(0)]],
    constant FieldFrame &frame [[buffer(1)]])
{
    FieldVertex v = vertices[vertexID];
    float4 world = frame.modelMatrix * v.position;
    FieldVarying out;
    out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;
    out.worldPosition = world.xyz;
    out.normal = normalize(v.normal.xyz);
    out.color = v.color;
    out.uv = v.uv.xy;
    return out;
}

fragment half4 field_fragment(
    FieldVarying in [[stage_in]],
    constant FieldFrame &frame [[buffer(1)]])
{
    float3 light = normalize(u_light.xyz);
    float diffuse = 0.2 + 0.8 * max(dot(normalize(in.normal), light), 0.0);

    // tube() writes uv.y along the spine, so this band travels with the curve.
    float band = 0.75 + 0.25 * sin(in.uv.y * 40.0 - fieldTime(frame) * 6.0);
    float3 rgb = in.color.rgb * diffuse * band * mix(0.8, 1.15, u_pulse.x);

    return half4(half3(rgb), half(in.color.a));
}
