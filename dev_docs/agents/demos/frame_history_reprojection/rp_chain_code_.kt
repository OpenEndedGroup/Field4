import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.addPassChain

prop<afield.four.fabricated.FabricateBuiltChain>(chain)

// `persistent` says WHAT last frame's pixels were. `frameHistory` says WHERE they were.
// `temporalAccumulate` declares both, and that composition is the whole point: without the
// second it accumulates correctly while the camera is still and smears the moment it moves.
//
// `resolution` must equal the chain input's size, or the reprojected UV addresses the right
// fraction of the wrong picture.
chain = bundle.addPassChain(
    FabricatePassLibrary.temporalAccumulate(
        name = "taa",
        source = FabricatePassChain.CHAIN_INPUT,
        blend = 0.92f,
        resolution = 1024 to 1024
    ) + FabricatePassLibrary.temporalDebug(
        name = "display",
        source = "taa",
        mode = 0
    ),
    printReport = true
)
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// The camera turns, so reprojection has something to do. A pure yaw about the camera's own
// position: `reprojection` is then P*R*inverse(P), which maps a ray to a ray whatever its
// depth, so the assumed NDC depth costs nothing here.
frames {
    while (true) {
        bundle.scene.camera.yaw(0.004, aroundTarget = false)
        frame()
    }
}
