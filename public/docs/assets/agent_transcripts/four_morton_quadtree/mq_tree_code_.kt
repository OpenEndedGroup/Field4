import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.FabricatePassSize
import afield.four.fabricated.FabricatePassSpec
import afield.four.fabricated.addPassChain
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT

prop<afield.four.fabricated.FabricateBuiltChain>(tree)

val grid = 16      // 16x16 = 256 points, one per texel of the point texture
val depth = 4      // 16x16 leaf cells, so 8 bits of Morton code

// The point set: three orbiting clusters, two of them stretched and leaning. One point
// per texel, as (x, y, weight, value) — the layout `mortonPointQuadtree` reads.
val pointsFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(push_constant) uniform PointPush {
        float time;
    } push;
    layout(location = 0) out vec4 outPoint;

    const int WIDTH = $grid;

    float hash1(float n) { return fract(sin(n) * 43758.5453123); }

    void main() {
        ivec2 p = ivec2(gl_FragCoord.xy);
        float i = float(p.y * WIDTH + p.x);
        int which = int(mod(i, 3.0));
        float t = push.time;

        vec2 centre = which == 0 ? vec2(0.34 + 0.06 * sin(t), 0.36)
                    : which == 1 ? vec2(0.68, 0.34 + 0.06 * cos(t * 0.8))
                                 : vec2(0.52 + 0.04 * sin(t * 0.5), 0.70);
        vec2 skew = which == 0 ? vec2(1.0, 1.0) : which == 1 ? vec2(2.0, 0.45) : vec2(0.45, 1.8);
        float lean = which == 0 ? 0.0 : which == 1 ? 0.5 + 0.3 * sin(t * 0.4) : -0.7 - 0.3 * cos(t * 0.3);
        float radius = which == 0 ? 0.10 : 0.06;

        float angle = hash1(i * 1.7 + 3.0) * 6.2831853;
        vec2 offset = vec2(cos(angle), sin(angle)) * (radius * pow(hash1(i * 2.3 + 11.0), 0.7)) * skew;
        vec2 turned = vec2(offset.x * cos(lean) - offset.y * sin(lean),
                           offset.x * sin(lean) + offset.y * cos(lean));

        outPoint = vec4(clamp(centre + turned, vec2(0.01), vec2(0.99)),
                        0.35 + 0.65 * hash1(i * 3.1 + 7.0),
                        0.2 + 0.7 * hash1(i * 5.9 + 19.0));
    }
"""

// A picture of the points to draw the tree over. Every texel gathers every point, which
// is a scatter turned inside out — the only way a fragment shader draws a point set, and
// the same reason the sort below is a gather.
val splatFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(location = 0) out vec4 outColor;

    const int COUNT = ${grid * grid};
    const int WIDTH = $grid;

    void main() {
        float mass = 0.0;
        float value = 0.0;
        for (int i = 0; i < COUNT; ++i) {
            vec4 point = texelFetch(sourceTex, ivec2(i % WIDTH, i / WIDTH), 0);
            float d = length(vUv - point.xy) / 0.02;
            float falloff = exp(-0.5 * d * d) * point.z;
            mass += falloff;
            value += falloff * point.w;
        }
        // The value is a *ratio* — Σwv/Σw — so it stays at full strength wherever the
        // mass is merely small rather than zero, and writing it into a channel of its own
        // paints the whole frame with the faintest halo of the cloud. Multiply it by the
        // mass: colour where there is something, black where there is not.
        float m = clamp(mass * 0.06, 0.0, 1.0);
        float v = mass > 0.0 ? value / mass : 0.0;
        outColor = vec4(mix(vec3(0.20, 0.35, 0.95), vec3(1.0, 0.85, 0.35), v) * m, 1.0);
    }
"""

// Declared every run, like its companion: `addPassChain` replaces by name, and nothing in
// this chain is persistent, so the whole tree simply rebuilds on the next frame.
tree = bundle.addPassChain(
    listOf(
        FabricatePassSpec(
            name = "points",
            fragment = pointsFragment,
            size = FabricatePassSize.Absolute(grid, grid),
            // fp32 throughout, and here it is load-bearing twice over: a Morton code and a
            // point index are exact integers rather than quantities, and a code that
            // rounds is a point that changes cell at the *top* of the tree.
            format = VK_FORMAT_R32G32B32A32_SFLOAT,
            source = FabricatePassChain.CHAIN_INPUT,
            push = mapOf("time" to 0f)
        ),
        FabricatePassSpec(
            name = "view",
            fragment = splatFragment,
            size = FabricatePassSize.Absolute(512, 512),
            format = VK_FORMAT_R32G32B32A32_SFLOAT,
            source = "points"
        )
    ) + FabricatePassLibrary.mortonPointQuadtree(
        prefix = "quadtree",
        points = "points",
        pointGrid = grid,
        depth = depth,
        // A quarter of the points can land in one leaf cell, whose density against a
        // uniform spread is then about 64. Correct, and four hundred times what an 8-bit
        // preview of that plane could show; this only affects looking at it.
        densityScale = 1f / 64f
    ) + FabricatePassLibrary.adaptiveSummaryCells(
        // The descent: from depth 1 down, stopping where a node already describes its
        // points well enough. SPREAD refines where the mass is *narrower than the cell
        // holding it* — which needs the second moments, and is therefore a criterion an
        // occupancy quadtree cannot express at all. Empty space stops at depth 1; a tight
        // cluster is chased to the leaves.
        prefix = "quadtree",
        name = "cut",
        over = "view",
        coarsest = 1,
        finest = depth,
        criterion = FabricatePassLibrary.SubdivisionCriterion.SPREAD,
        threshold = 0.2f,
        weightGain = 40f
    ),
    printReport = true
)
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// ── live knobs. One bound lambda moves the points, and everything downstream — codes,
//    ranks, the sorted array, every node's range and every level of the summary — is
//    recomputed from them on each frame.
tree["time"] = { renderer.frameCounter * 0.01f }
tree["sigma"] = 1.2f
tree["weightGain"] = 40f
tree["underGain"] = 0.5f
// Reaches every level of the descent at once: one number re-cuts the whole tree on the
// next frame, with nothing rebuilt and nothing reallocated.
tree["threshold"] = 0.2f

println("morton quadtree: ${tree.names().size} passes over ${grid * grid} points, " +
        "leaf level ${tree.targetOf("quadtree-range-$depth")?.width}² nodes")
