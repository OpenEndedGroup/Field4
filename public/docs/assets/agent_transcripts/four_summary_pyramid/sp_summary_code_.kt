import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.FabricatePassSize
import afield.four.fabricated.FabricatePassSpec
import afield.four.fabricated.addPassChain
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT

prop<afield.four.fabricated.FabricateBuiltChain>(summary)

// The field being summarised: three anisotropic blobs that drift and rotate, with the
// mass in alpha and a value ramp in red. A chain head that synthesises its own input is
// self-contained whatever the post stack looked like before it.
val fieldFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(push_constant) uniform FieldPush {
        float time;
    } push;
    layout(location = 0) out vec4 outColor;

    float blob(vec2 uv, vec2 centre, float lean, vec2 radii) {
        vec2 d = uv - centre;
        float s = sin(lean);
        float c = cos(lean);
        vec2 r = vec2(d.x * c + d.y * s, -d.x * s + d.y * c) / radii;
        return exp(-dot(r, r));
    }

    void main() {
        float t = push.time;
        float mass = blob(vUv, vec2(0.32 + 0.05 * sin(t), 0.34), t * 0.5, vec2(0.10, 0.035))
                   + blob(vUv, vec2(0.70, 0.36 + 0.05 * cos(t * 0.8)), -t * 0.3, vec2(0.05, 0.05))
                   + blob(vUv, vec2(0.50, 0.72), 0.7 + 0.25 * sin(t * 0.6), vec2(0.13, 0.03));
        // A Gaussian is never actually zero, so without a floor every cell in the frame
        // has a weight of about 1e-20 — which is over the overlay's own threshold, and
        // draws an ellipse of the rounding error in an empty cell. The picture is then a
        // grid of ellipses everywhere and the interesting ones are lost in it.
        mass = mass < 0.01 ? 0.0 : clamp(mass, 0.0, 1.0);
        // The value is deliberately NOT the mass: a summary carries a value *and* a
        // weight, and keeping them different is what makes "the mean value where the mass
        // is" a different question from "how much mass".
        float value = 0.25 + 0.7 * vUv.y;
        outColor = vec4(value, mass * 0.45, mass, mass);
    }
"""

// The field as a *picture*, which is not the same thing as the field. `field` carries a
// value in red over the whole frame because that is what the pyramid weighs and averages;
// looking at it directly is looking at a red wash with the interesting part in the alpha
// channel. One pass turns it into something to draw on: the value's colour, where the
// mass is.
val viewFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(location = 0) out vec4 outColor;

    void main() {
        vec4 f = texture(sourceTex, vUv);
        vec3 warm = mix(vec3(0.65, 0.16, 0.06), vec3(1.0, 0.82, 0.30), f.r);
        outColor = vec4(warm * f.a, 1.0);
    }
"""

// Declared every run. `addPassChain` replaces passes of the same name and reuses a target
// whose shape has not changed, so editing a number and hitting run remakes the chain
// without reallocating anything — which is why there is no `once` here. Wrapping the
// declaration in one would mean a `resolution` you edited never took effect.
summary = bundle.addPassChain(
    listOf(
        FabricatePassSpec(
            name = "field",
            fragment = fieldFragment,
            size = FabricatePassSize.Absolute(256, 256),
            // fp32, and it matters here rather than being caution: the second moments are
            // differences that need every bit of the first ones, and an 8-bit field would
            // quantise the mass before the pyramid ever saw it.
            format = VK_FORMAT_R32G32B32A32_SFLOAT,
            source = FabricatePassChain.CHAIN_INPUT,
            push = mapOf("time" to 0f)
        ),
        FabricatePassSpec(
            name = "field-view",
            fragment = viewFragment,
            size = FabricatePassSize.Absolute(512, 512),
            source = "field"
        )
    ) + FabricatePassLibrary.summaryPyramid(
        // Nine levels per plane, three planes: the leaf is 256² and the root is one cell
        // holding the statistics of the whole field.
        prefix = "summary",
        resolution = 256,
        source = "field",
        weightChannel = 3,
        valueChannel = 0
    ) + FabricatePassLibrary.summaryEllipses(
        // The overlay runs at the *frame's* resolution while reading a 16x16 level of the
        // tree, which is the combination the pass chain layer exists for.
        name = "summary-ellipses",
        over = "field-view",
        prefix = "summary",
        depth = 4,
        weightGain = 3f
    ),
    printReport = true
)
// The frame graph has to re-sync before the new passes are scheduled.
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// ── live knobs ──
//
// A lambda *binds*: the supplier is re-read every time the push buffer is written, so
// this one line is the whole animation and there is nothing to call per frame. The whole
// pyramid is rebuilt from the field every frame — twenty-seven passes over 256² and down,
// which is cheaper than the blur in four_pass_chain.
summary["time"] = { renderer.frameCounter * 0.01f }
summary["sigma"] = 1f           // the ellipse's radius, in standard deviations
summary["weightGain"] = 3f      // a mean coverage is a small number; this lifts the fill
summary["underGain"] = 0.8f     // dims the field so the overlay reads against it

println("summary pyramid: ${summary.names().size} passes, " +
        "root ${summary.targetOf("summary-value-0")?.width}x" +
        "${summary.targetOf("summary-value-0")?.height}")
