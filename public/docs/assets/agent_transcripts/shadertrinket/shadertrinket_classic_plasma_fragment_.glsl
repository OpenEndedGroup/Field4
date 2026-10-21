{{ shadertoy.image }}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = fragCoord / iResolution.xy;
  vec2 p = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
  float t = iTime * 0.75;

  float v = sin(p.x * 7.0 + t);
  v += sin((p.y + sin(t * 0.7)) * 8.0);
  v += sin((p.x + p.y) * 5.0 + t * 1.7);
  v += sin(length(p + vec2(sin(t), cos(t * 0.6))) * 11.0 - t * 2.0);

  vec3 col = 0.5 + 0.5 * cos(vec3(0.0, 1.7, 3.4) + v + t);
  col *= 0.65 + 0.35 * smoothstep(1.2, 0.0, length(p));
  col += pow(max(0.0, 1.0 - length(uv - 0.5) * 1.35), 4.0) * vec3(0.35, 0.48, 0.65);
  fragColor = vec4(col, 1.0);
}
