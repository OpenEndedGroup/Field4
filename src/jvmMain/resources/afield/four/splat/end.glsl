// splat/end — the second half of a SplatShader's compute tab; see splat/insert.

    // ---- your statements end here ----

    splatFinish(splatSlot, pos, scales, rgba, quat);
}

void splatFinish(uint slot, vec3 pos, vec3 scales, vec4 rgba, vec4 quat) {
    rgba = clamp(rgba, vec4(0.0), vec4(1.0));
    scales = max(scales, vec3(0.0001));

    // A rotation with no length is the identity, as Spark treats it.
    float len = length(quat);
    vec4 q = len > 1e-12 ? quat / len : vec4(0.0, 0.0, 0.0, 1.0);
    float x = q.x, y = q.y, z = q.z, w = q.w;

    // Columns of R, so that R * S * S * transpose(R) is the covariance.
    mat3 r = mat3(
        1.0 - 2.0 * (y * y + z * z), 2.0 * (x * y + w * z), 2.0 * (x * z - w * y),
        2.0 * (x * y - w * z), 1.0 - 2.0 * (x * x + z * z), 2.0 * (y * z + w * x),
        2.0 * (x * z + w * y), 2.0 * (y * z - w * x), 1.0 - 2.0 * (x * x + y * y)
    );
    mat3 rs = r * mat3(scales.x, 0.0, 0.0, 0.0, scales.y, 0.0, 0.0, 0.0, scales.z);
    mat3 cov = rs * transpose(rs);

    uint base = slot * 4u;
    // A non-finite centre would poison the sort; drop the splat instead.
    bool finite = !any(isnan(pos)) && !any(isinf(pos));
    splatPush.splatsOut.v[base + 0u] = vec4(pos, finite ? rgba.a : 0.0);
    splatPush.splatsOut.v[base + 1u] = vec4(rgba.rgb, 0.0);
    splatPush.splatsOut.v[base + 2u] = vec4(cov[0][0], cov[0][1], cov[0][2], cov[1][1]);
    splatPush.splatsOut.v[base + 3u] = vec4(cov[1][2], cov[2][2], 0.0, 0.0);
}
