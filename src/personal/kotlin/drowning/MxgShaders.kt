package drowning

/** Editable starting shaders for an MXG box; dump them with `MxgMainKt --shaders DIR`. */
object MxgShaders {
    val VERTEX = """
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) readonly buffer MxgNodes    { vec4 raw[]; };
layout(std430, buffer_reference) readonly buffer MxgSegments { vec4 raw[]; };

layout(push_constant) uniform MxgPush {
    mat4 p;
    MxgNodes nodes;
    MxgSegments segments;
    uint drawMode;             // 0 contours, 1 tree edges, 2 nodes
    uint nodeCount;
    vec2 viewport;
    float contourWidthPixels;
    float edgeWidthPixels;
    float nodeRadiusPixels;
    float maximumRadiusPixels;
    float contourAlpha;
    float edgeAlpha;
    float nodeAlpha;
    float holesAlpha;
    float dynamicsGain;
    float levelFloor;
    float levelCeiling;
    float quantizationLevels;
} pc;

layout(location = 0) out flat vec4 vColor;
layout(location = 1) out vec2 vLocal;
layout(location = 2) out flat uint vMode;

const vec2 QUAD[6] = vec2[6](
    vec2(0.0, -1.0), vec2(1.0, -1.0), vec2(1.0,  1.0),
    vec2(1.0,  1.0), vec2(0.0,  1.0), vec2(0.0, -1.0)
);

vec4 nodeRaw(uint node, uint word) { return pc.nodes.raw[node * 6u + word]; }
vec2 nodeCentroid(uint node) { return nodeRaw(node, 0u).xy; }
uvec4 nodeTopology(uint node) { return floatBitsToUint(nodeRaw(node, 4u)); }
uvec4 nodeTracking(uint node) { return floatBitsToUint(nodeRaw(node, 5u)); }

void lineQuad(vec2 a, vec2 b, float widthPixels, vec2 corner) {
    vec4 ca = pc.p * vec4(a, 0.0, 1.0);
    vec4 cb = pc.p * vec4(b, 0.0, 1.0);
    vec2 na = ca.xy / ca.w;
    vec2 nb = cb.xy / cb.w;
    vec2 deltaPixels = (nb - na) * 0.5 * pc.viewport;
    vec2 normalPixels = length(deltaPixels) > 1e-5
        ? normalize(vec2(-deltaPixels.y, deltaPixels.x)) : vec2(0.0, 1.0);
    vec2 offsetNdc = normalPixels * widthPixels * 2.0 / max(pc.viewport, vec2(1.0));
    vec4 chosen = mix(ca, cb, corner.x);
    vec2 ndc = mix(na, nb, corner.x) + offsetNdc * corner.y;
    gl_Position = vec4(ndc * chosen.w, chosen.z, chosen.w);
    vLocal = corner;
}

void main() {
    uint primitive = uint(gl_VertexIndex) / 6u;
    vec2 corner = QUAD[uint(gl_VertexIndex) % 6u];
    vMode = pc.drawMode;

    if (pc.drawMode == 0u) {
        vec4 segment = pc.segments.raw[primitive * 2u];
        uvec4 meta = floatBitsToUint(pc.segments.raw[primitive * 2u + 1u]);
        vec4 stats = nodeRaw(meta.x, 1u);
        float levels = max(pc.quantizationLevels - 1.0, 1.0);
        float tone = mix(pc.levelFloor, pc.levelCeiling, clamp(stats.z / levels, 0.0, 1.0));
        bool hole = (meta.w & 1u) != 0u;
        vColor = vec4(vec3(tone), hole ? pc.holesAlpha : pc.contourAlpha);
        lineQuad(segment.xy, segment.zw, pc.contourWidthPixels, corner);
        return;
    }

    if (pc.drawMode == 1u) {
        uint node = primitive + 1u;
        uint parent = nodeTopology(node).x;
        vColor = vec4(vec3(0.72), pc.edgeAlpha);
        lineQuad(nodeCentroid(parent), nodeCentroid(node), pc.edgeWidthPixels, corner);
        return;
    }

    uint node = primitive;
    vec4 stats = nodeRaw(node, 1u);
    uvec4 tracking = nodeTracking(node);
    bool maximum = (tracking.w & 1u) != 0u;
    float radius = maximum ? pc.maximumRadiusPixels : pc.nodeRadiusPixels;
    radius *= clamp(1.0 + stats.w * pc.dynamicsGain, 1.0, 3.0);
    vec2 signedCorner = vec2(corner.x * 2.0 - 1.0, corner.y);
    vec4 center = pc.p * vec4(nodeCentroid(node), 0.0, 1.0);
    vec2 ndc = center.xy / center.w + signedCorner * radius * 2.0 / max(pc.viewport, vec2(1.0));
    gl_Position = vec4(ndc * center.w, center.z, center.w);
    vLocal = signedCorner;
    float levels = max(pc.quantizationLevels - 1.0, 1.0);
    float tone = mix(pc.levelFloor, pc.levelCeiling, clamp(stats.z / levels, 0.0, 1.0));
    vColor = vec4(vec3(maximum ? 1.0 : tone), pc.nodeAlpha);
}
""".trimIndent()

    val FRAGMENT = """
#version 450

layout(push_constant) uniform MxgPush {
    mat4 p;
    uvec2 nodesAddress;
    uvec2 segmentsAddress;
    uint drawMode;
    uint nodeCount;
    vec2 viewport;
    float contourWidthPixels;
    float edgeWidthPixels;
    float nodeRadiusPixels;
    float maximumRadiusPixels;
    float contourAlpha;
    float edgeAlpha;
    float nodeAlpha;
    float holesAlpha;
    float dynamicsGain;
    float levelFloor;
    float levelCeiling;
    float quantizationLevels;
} pc;

layout(location = 0) in flat vec4 vColor;
layout(location = 1) in vec2 vLocal;
layout(location = 2) in flat uint vMode;
layout(location = 0) out vec4 uFragColor;

void main() {
    if (vMode == 2u && dot(vLocal, vLocal) > 1.0) discard;
    uFragColor = vColor;
}
""".trimIndent()
}
