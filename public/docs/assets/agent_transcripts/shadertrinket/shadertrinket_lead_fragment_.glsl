{{ shadertoy.image }}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  float t = iTime * 0.7;
  vec3 col = vec3(0.015, 0.018, 0.018);

  float grid = mod(floor((uv.x + 2.0) * 9.0) + floor((uv.y + 2.0) * 9.0), 2.0);
  if (grid < 0.5) col = vec3(0.025, 0.03, 0.03);

  for (int i = 0; i < 8; i++) {
    float fi = float(i);
    vec2 c = vec2(cos(t + fi * 1.35), sin(t * 0.8 + fi * 1.1)) * 0.42;
    float r = 0.17 + 0.035 * step(0.5, mod(fi, 2.0));
    float ring = 1.0 - step(0.018, abs(length(uv - c) - r));
    float dotMask = 1.0 - step(0.035, length(uv - c));

    if (ring > 0.5) {
      if (mod(fi, 3.0) < 1.0) col = vec3(0.16, 0.72, 0.62);
      else if (mod(fi, 3.0) < 2.0) col = vec3(0.95, 0.74, 0.25);
      else col = vec3(0.35, 0.50, 0.95);
    }
    if (dotMask > 0.5) col = vec3(0.92, 0.96, 0.90);
  }

  fragColor = vec4(col, 1.0);
}
