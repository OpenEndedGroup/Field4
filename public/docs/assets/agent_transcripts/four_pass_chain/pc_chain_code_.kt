import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.addPassChain

// Fully qualified for the same reason as the scene box: `prop<>`'s rewrite loses the
// snippet's imports.
prop<afield.four.fabricated.FabricateBuiltChain>(chain)

val r: Renderer = fromContext()
val b: StandardRenderer.Bundle = fromContext()

// Declared once. Six passes, each with its own resolution: reduce to a half, reduce to a
// quarter, two aperture discs on the cheap quarter-resolution image, and a composite that
// rejoins at full resolution while sampling the small blur. `printReport` prints the plan
// — sizes, sampler wiring, shared targets, and the memory cost against one full-resolution
// target per pass. Read it before believing the picture: a chain planned at the wrong
// resolution renders something that looks perfectly plausible.
once.chain {
    val c = b.addPassChain(
        FabricatePassLibrary.apertureChain(
            nearRadius = 4f,
            farRadius = 12f,
            taps = 40,
            blend = 0.92f
        ),
        renderer = r,
        printReport = true
    )
    b.attachTo(r)
    b.present(renderer = r)
    c
}

// ── live knobs. Edit a number, hit run. No rebuild, no reallocation. ──
chain.setPush("aperture-near", "radius", 4f)
chain.setPush("aperture-far", "radius", 14f)
chain.setPush("composite", "blend", 0.92f)

println(chain.plan.report())
