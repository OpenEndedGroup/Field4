#version 450
layout(location = 0) out vec4 uFragColor;

layout(push_constant) uniform matrices {
    float time;
} pc;

layout(set=0, binding = 0) uniform sampler2D otherView;

void main()
{
    // 1024 is the target's default size, so this maps pixels to texture coordinates.
    vec2 tc = gl_FragCoord.xy / 1024.0;
    vec2 px = vec2(1.0 / 1024.0);

    // Last frame, blurred hard and faded a little: a 3 x 3 kernel reaching 5 pixels out each way, so
    // whatever was drawn spreads out visibly from one frame to the next and slowly dies away.
    vec3 c = vec3(0);
    float total = 0.0;
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
            float w = (x == 0 && y == 0) ? 4.0 : ((x == 0 || y == 0) ? 2.0 : 1.0);
            c += w * texture(otherView, tc + 5.0 * px * vec2(x, y)).rgb;
            total += w;
        }
    c = c / total * 0.996;

    // A dot moving on a Lissajous path, its colour cycling, stamped into the feedback every frame.
    vec2 at = vec2(0.5) + 0.32 * vec2(cos(pc.time * 0.9), sin(pc.time * 1.3));
    vec3 ink = 0.5 + 0.5 * cos(pc.time + vec3(0, 2, 4));
    c = max(c, ink * smoothstep(0.025, 0.0, length(tc - at)));

    uFragColor = vec4(c, 1.0);
}
