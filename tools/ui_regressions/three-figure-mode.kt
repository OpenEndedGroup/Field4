// A three scene with the viewer's defaults: grey background, HUD on.
three(name = "figuremode", width = 480, height = 320) {
    mesh(icosphereMesh(2, 1.0).withColor(1.0, 0.3, 0.2), wireframe = false)
}
