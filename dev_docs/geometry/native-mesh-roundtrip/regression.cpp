// Standalone Manifold import regression. No project meshes or recipes are used.
#include <manifold/manifold.h>
#include <manifold/mesh.h>
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <iostream>

using namespace manifold;

// An oriented triangle is replaced with a coplanar fan. When the new vertex
// lies outside the triangle, the fan folds back over itself. Its oriented
// boundary and enclosed material still describe the same box.
MeshGL64 foldedFace(const MeshGL64& input, size_t triangle, double u, double v) {
  MeshGL64 mesh;
  mesh.numProp = 3;
  mesh.vertProperties = input.vertProperties;
  mesh.triVerts = input.triVerts;
  const auto ids = mesh.GetTriVerts(triangle);
  const auto a = mesh.GetVertPos(ids[0]);
  const auto b = mesh.GetVertPos(ids[1]);
  const auto c = mesh.GetVertPos(ids[2]);
  const auto p = a + u * (b - a) + v * (c - a);
  const uint64_t added = mesh.NumVert();
  mesh.vertProperties.insert(mesh.vertProperties.end(), {p.x, p.y, p.z});
  mesh.triVerts.erase(mesh.triVerts.begin() + 3 * triangle,
                     mesh.triVerts.begin() + 3 * triangle + 3);
  mesh.triVerts.insert(mesh.triVerts.end(),
                      {ids[0], ids[1], added, ids[1], ids[2], added,
                       ids[2], ids[0], added});
  return mesh;
}

int main() {
  int cases = 0, failures = 0, boundaryFailures = 0;
  double worstDifference = 0;
  const double tolerance = 1e-7;
  for (int pose = 0; pose != 3; ++pose) {
    auto reference = Manifold::Cube(vec3(2, 3, 7));
    if (pose > 0) reference = reference.Rotate(17, 31, 23);
    if (pose > 1) reference = reference.Translate(vec3(100, -50, 80));
    const auto original = reference.GetMeshGL64();
    for (size_t face = 0; face < original.NumTri(); ++face)
      for (double u : {-4., -1., .5, 2., 4.})
        for (double v : {-4., -1., .5, 2., 4.}) {
          ++cases;
          Manifold imported(foldedFace(original, face, u, v));
          bool passed = true;
          // Keep both native handles alive. A scalar volume comparison alone
          // would miss equal-sized regions lost and gained elsewhere.
          for (int generation = 0; generation != 3; ++generation) {
            const auto missing = reference - imported;
            const auto extra = imported - reference;
            const double difference = std::abs(missing.Volume()) + std::abs(extra.Volume());
            worstDifference = std::max(worstDifference, difference);
            passed &= imported.Status() == Manifold::Error::NoError &&
                      missing.Status() == Manifold::Error::NoError &&
                      extra.Status() == Manifold::Error::NoError &&
                      std::isfinite(difference) && difference < tolerance &&
                      std::abs(imported.Volume() - 42) < tolerance;
            const auto clean = imported.AsOriginal().Simplify();
            const auto bounds = clean.BoundingBox();
            const auto expected = reference.BoundingBox();
            bool boundaryOK = length(bounds.min - expected.min) < tolerance &&
                              length(bounds.max - expected.max) < tolerance;
            const auto mesh = clean.GetMeshGL64();
            const auto centre = (expected.min + expected.max) / 2.0;
            for (size_t t = 0; t < mesh.NumTri(); ++t) {
              const auto ids = mesh.GetTriVerts(t);
              const auto a = mesh.GetVertPos(ids[0]);
              const auto b = mesh.GetVertPos(ids[1]);
              const auto c = mesh.GetVertPos(ids[2]);
              boundaryOK &= dot(cross(b-a, c-a), (a+b+c)/3.0-centre) >= -tolerance;
            }
            const double cleanedDifference = std::abs((clean-reference).Volume()) +
                                             std::abs((reference-clean).Volume());
            boundaryOK &= clean.Status() == Manifold::Error::NoError &&
                          std::isfinite(cleanedDifference) && cleanedDifference < tolerance;
            if (!boundaryOK) ++boundaryFailures;
            passed &= boundaryOK;
            imported = Manifold(imported.GetMeshGL64());
          }
          if (!passed) {
            ++failures;
            if (failures <= 12)
              std::cout << "failed pose=" << pose << " face=" << face
                        << " u=" << u << " v=" << v << '\n';
          }
        }
  }
  std::cout << std::setprecision(17) << "{\"cases\":" << cases
            << ",\"failures\":" << failures
            << ",\"boundaryFailures\":" << boundaryFailures
            << ",\"worstSymmetricDifference\":" << worstDifference << "}\n";
  return failures == 0 ? 0 : 1;
}
