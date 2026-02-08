void main() {
    vec2 uv = gl_FragCoord.xy / vec2(1920.0, 1080.0);
    gl_FragColor = vec4(uv.x, uv.y, 0.5, 1.0);
}
