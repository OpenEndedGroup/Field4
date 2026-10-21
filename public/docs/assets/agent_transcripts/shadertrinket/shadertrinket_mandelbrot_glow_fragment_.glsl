{{ shadertoy.image }}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  float zoom = 0.72 + 0.18 * sin(iTime * 0.18);
  vec2 c = uv * zoom + vec2(-0.68, 0.0);
  c += vec2(0.08 * sin(iTime * 0.21), 0.05 * cos(iTime * 0.17));
  vec2 z = vec2(0.0);
  float iter = 0.0;
  float trap = 10.0;

  for (int i = 0; i < 96; i++) {
    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
    trap = min(trap, abs(z.x * z.y));
    if (dot(z, z) > 16.0) {
      iter = float(i);
      break;
    }
  }

  float m = iter / 96.0;
  vec3 col = 0.5 + 0.5 * cos(vec3(0.4, 2.2, 4.2) + m * 8.0 + iTime * 0.25);
  col *= smoothstep(0.0, 0.04, m);
  col += exp(-trap * 45.0) * vec3(0.25, 0.9, 1.15);
  fragColor = vec4(col, 1.0);
}
