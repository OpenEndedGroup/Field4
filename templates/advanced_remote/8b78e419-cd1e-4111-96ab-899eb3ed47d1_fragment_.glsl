precision highp float;
precision highp int;

// The remote JavaScript scene supplies UVs and the video texture.
varying vec3 vNormal;
varying vec3 pos;
varying vec2 vUv;
uniform sampler2D map;

void main() {
    // Keep the sampled RGB opaque; remove pixels whose blue channel exceeds the key.
    gl_FragColor = vec4(texture2D(map, vUv).rgb, 1.0);
    if (gl_FragColor.b > 0.9) discard;
}
