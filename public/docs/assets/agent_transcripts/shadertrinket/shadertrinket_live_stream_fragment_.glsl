{{ shadertoy.image }}

// Live Stream Input Demo: iChannel2 is the stream picked in this box's list (ShaderTrinket's
// Receive). The Publish box shares this canvas as @you/field-shader for the class to pick.

float hash(vec2 p) {
  p = fract(p * vec2(234.34, 435.35));
  p += dot(p, p + 34.23);
  return fract(p.x * p.y);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
  vec2 uv = fragCoord / iResolution.xy;
  vec2 p = (2.0 * fragCoord - iResolution.xy) / iResolution.y;

  vec3 remote = texture(iChannel2, uv).rgb;
  float signal = smoothstep(0.01, 0.16, dot(remote, vec3(0.333)));

  float lane = floor(uv.y * 12.0);
  float bars = step(0.82, hash(vec2(lane, floor(iTime * 5.0))));
  float sweep = smoothstep(0.02, 0.0, abs(fract(uv.x * 5.0 - iTime * 0.35) - 0.5));
  vec3 fallback = mix(vec3(0.015, 0.018, 0.028), vec3(0.08, 0.36, 0.40), bars);
  fallback += sweep * vec3(0.9, 0.62, 0.18) * smoothstep(1.35, 0.0, length(p));

  // The procedural field remains visible until a remote stream arrives.
  vec3 color = mix(fallback, remote, signal);
  color += signal * vec3(0.18, 0.75, 0.64) * sweep;
  color *= 0.75 + 0.25 * sin(iTime + uv.x * 9.0);
  fragColor = vec4(color, 1.0);
}
