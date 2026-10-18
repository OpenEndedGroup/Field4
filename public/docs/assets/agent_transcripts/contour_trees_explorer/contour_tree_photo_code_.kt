import afield.geometry.topology.trees.*

// The leaf print's brightness, divided into the regions of its contour tree, lightly and strongly simplified.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236)
val tree = ContourTrees.contourTree(print.luma().gaussianBlur(1.5))
val light = tree.simplify(persistence = 0.01)
val strong = tree.simplify(persistence = 0.05)
showImgGrid(
    "photograph" to print.scaleBy(2.0),
    "${light.arcs.size} arcs" to light.segmentationImage().scaleBy(2.0),
    "${strong.arcs.size} arcs" to strong.segmentationImage().scaleBy(2.0),
    columns = 3, cellSide = 260,
)
