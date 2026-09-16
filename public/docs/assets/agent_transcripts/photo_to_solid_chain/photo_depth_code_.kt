import afield.examples.chain.PhotoToSolidChain

// No depth camera anywhere. One still photograph in, one single-channel map out.
val photo = imgFromFile(PhotoToSolidChain.PHOTO, 3).scaleBy(0.45)
val (depth, model) = PhotoToSolidChain.depthMapOf(photo)

// The output is inverse *relative* depth in arbitrary units, so it has to be normalised
// against a window; depthMapOf does that against this frame's own range and prints it.
// Larger means NEARER, which is why the near floor is the bright band at the bottom.
println("photo ${photo.width}x${photo.height} -> depth ${depth.width}x${depth.height}")
println("${model.lastInferenceMs}ms on ${model.activeProvider}, input forced to a multiple of 14")

showImgGrid(
    "photograph ${photo.width}x${photo.height}" to photo,
    "depth ${depth.width}x${depth.height} — bright is near" to depth.colormap("inferno"),
    columns = 2,
    cellSide = 520,
)
