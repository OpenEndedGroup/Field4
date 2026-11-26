import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT

// A 640 x 360 float target, drawn every frame.
prop<RenderToTexture>(particleImage) = RenderToTexture {
    width = 640
    height = 360
    useDepth = false
    format = VK_FORMAT_R32G32B32A32_SFLOAT
}
renderer.renderList += "oidn particles" to particleImage

// 3000 soft discs on a three-armed spiral. Each fragment is kept with probability equal to
// the disc's opacity there, and drawn opaque: stochastic transparency, one sample a pixel.
val module = Module(
    parameters(renderer) {
        name = "oidn-particles"
        +particleImage
        depthTest = false
        depthWrite = false
        blend = false
        vertex =
            """#version 450
        layout(location=0) out vec2 tc;
        layout(location=1) flat out int id;
        layout(location=2) flat out vec3 col;
        const vec2 corner[6] = vec2[](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));
        float h(float n) { return fract(sin(n) * 43758.5453); }
        void main() {
            int i = gl_VertexIndex / 6;
            float f = float(i) + 1.0;
            id = i;
            tc = corner[gl_VertexIndex % 6];
            float r = 0.08 + 0.8 * h(f * 5.3);
            float a = r * 5.0 + floor(h(f * 3.1) * 3.0) * 2.0944 + (h(f * 7.7) - 0.5) * 0.7;
            float s = 0.03 + 0.09 * h(f * 9.1);
            col = mix(vec3(1.0, 0.5, 0.15), vec3(0.25, 0.55, 1.0), r) * (0.5 + h(f * 11.3));
            vec2 p = vec2(cos(a), sin(a)) * r + (tc - 0.5) * s;
            gl_Position = vec4(p.x * 360.0 / 640.0, p.y, 0.5, 1.0);
        }"""
        fragment =
            """#version 450
        layout(location=0) in vec2 tc;
        layout(location=1) flat in int id;
        layout(location=2) flat in vec3 col;
        layout(location=0) out vec4 color;
        float hash(uvec3 v) {
            v = v * 1664525u + 1013904223u;
            v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
            v ^= v >> 16u;
            v.x += v.y * v.z;
            return float(v.x) / 4294967295.0;
        }
        void main() {
            float d = length(tc * 2.0 - 1.0);
            float opacity = d < 1.0 ? 0.6 * exp(-4.0 * d * d) : 0.0;
            if (hash(uvec3(gl_FragCoord.xy, id)) >= opacity) discard;
            color = vec4(col, 1.0);
        }"""
    },
)
val discs = Mesh(renderer, module.pointer())
discs.resize(3000 * 6, 0, module.pointer())
module.meshes["discs"] = discs
particleImage.modules["discs"] = module.pointer()
println("drawing 3000 particles into ${particleImage.width} x ${particleImage.height}")
