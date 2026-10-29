/*
geometry { } is a recipe of triangles. Running the box runs the recipe and draws it.

  mat(...)             the material for triangles that follow
  color(...)           the colour for vertices that follow
  tri(a, b, c)         one triangle
  quad(a, b, c, d)     two triangles
  surface(...)         a grid of triangles
  revolve, tube, box   more triangles
  push() / pop() with translate, rotateX/Y/Z, scale

geometryAgent() teaches this document's AI4 chat the language and lets it look at every view here:
open the AI panel and ask for "a coral reef of tubes", or "make the terrain stormier".
*/
geometryAgent()

geometry {
    view(background = "#05080a", ground = true, grid = true)

    mesh("ripple terrain")
    mat(color = "#ffffff", roughness = 0.86, metalness = 0.03, flat = true)

    surface(78, 78) { u, v ->
        val x = (u - 0.5) * 7.2
        val z = (v - 0.5) * 7.2
        val r = sqrt(x * x + z * z)
        val waves = sin(r * 3.6) * 0.22
        val folds = noise(x * 0.75, z * 0.75) * 0.38
        val y = waves + folds
        vertex(x, y, z, hsl(188 + y * 56 + r * 5, 0.74, 0.42 + y * 0.08))
    }
}
