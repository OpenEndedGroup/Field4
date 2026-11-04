#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
void main()
{
    // d is 0.5 at the square's centre, falling to 0 at distance 0.5 from it
    float d = max(0.0, 0.5-length(tc-vec2(0.5)));
    // A positive pixel feather keeps both smoothstep intervals well defined.
    float w = max(fwidth(d), 1e-6);
    // d increases inward: enter the outer rim, then fade into the inner hole.
    float coverage = smoothstep(0.1, 0.1+w, d) *
    (1.0 - smoothstep(0.14, 0.14+w, d));
    uFragColor = vec4(1.0, 0.8, 0.4, coverage*0.2);
    // skip pixels outside the ring entirely
    if (coverage<0.01) discard;
}
