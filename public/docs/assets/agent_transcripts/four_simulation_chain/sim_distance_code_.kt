import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassInput
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.FabricatePassSize
import afield.four.fabricated.FabricatePassSpec
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.addPassChain
import afield.stdlib.linalg.Vector2f

prop<afield.four.fabricated.FabricateBuiltChain>(sdf)

val r: Renderer = fromContext()
val b: StandardRenderer.Bundle = fromContext()

// The mask: a disc and a bar, in every channel. A chain head that synthesises its own
// input is self-contained however the chains before it ended up looking.
val maskFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(push_constant) uniform MaskPush {
        vec2 centre;
        float radius;
        float bar;
    } push;
    layout(location = 0) out vec4 outColor;

    void main() {
        float disc = length(vUv - push.centre) <= push.radius ? 1.0 : 0.0;
        float bar = (abs(vUv.y - 0.78) < push.bar && vUv.x > 0.2 && vUv.x < 0.8) ? 1.0 : 0.0;
        float m = max(disc, bar);
        outColor = vec4(m, m, m, m);
    }
"""

// The inset. Two chains in one post stack run in series, so whichever is appended last owns
// the screen — append this one after the fluid and the fluid simply disappears. This pass
// runs at full resolution, passes the incoming frame (the fluid) through, and draws the
// distance field into the top-right *square* quadrant, so both simulations are visible and
// the field keeps its aspect ratio. It is also a worked full-resolution rejoin with a named
// extra input, which is the shape every composite in the library has.
val insetFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(set = 0, binding = 1) uniform sampler2D bandsTex;
    layout(location = 0) out vec4 outColor;

    void main() {
        vec4 under = texture(sourceTex, vUv);
        if (vUv.x > 0.5 && vUv.y < 0.5) {
            vec2 q = vec2((vUv.x - 0.5) * 2.0, vUv.y * 2.0);
            outColor = vec4(texture(bandsTex, q).rgb, under.a);
        } else {
            outColor = under;
        }
    }
"""

once.sdf {
    val mask = FabricatePassSpec(
        name = "mask",
        fragment = maskFragment,
        size = FabricatePassSize.Absolute(256, 256),
        source = FabricatePassChain.CHAIN_INPUT,
        push = mapOf("centre" to Vector2f(0.38f, 0.38f), "radius" to 0.16f, "bar" to 0.02f)
    )
    // Seed, nine flood rounds at halving stride, JFA+1, then the distance — and `signed`,
    // which seeds the *boundary* rather than the interior so one flood gives a field that
    // is negative inside and positive out.
    val c = b.addPassChain(
        listOf(mask) + FabricatePassLibrary.jumpFloodDistance(
            prefix = "sdf",
            resolution = 256,
            source = "mask",
            signed = true,
            scale = 1f / 512f
        ) + FabricatePassLibrary.distanceBands(
            name = "sdf-bands",
            source = "sdf-distance",
            spacing = 0.03f
        ) + FabricatePassSpec(
            name = "sdf-inset",
            fragment = insetFragment,
            // Sized from its primary input, which is the full-resolution frame, while
            // sampling the 256² field. That combination is the whole point of the layer.
            size = FabricatePassSize.MatchSource,
            source = FabricatePassChain.CHAIN_INPUT,
            extraInputs = listOf(FabricatePassInput("bandsTex", "sdf-bands"))
        ),
        renderer = r,
        printReport = true
    )
    b.attachTo(r)
    b.present(renderer = r)
    c
}

// ── live knobs. Move the shape and the whole field re-floods, from scratch, every frame. ──
sdf.setPush("mask", "centre", Vector2f(0.38f, 0.38f))
sdf.setPush("mask", "radius", 0.16f)
sdf.setPush("sdf-bands", "spacing", 0.03f)

println("distance field: ${sdf.names().size} passes, output ${sdf.output.width}²")
