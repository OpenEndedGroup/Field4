var module = moduleHere {
    primitiveType = triangles
}
var m = Mesh()
module += "particles" to m
renderer += "cuda particles" to module

// Published by the CUDA box above with `prop`.
m.particles = { cudaParticles }
m.aspectRatio = { window.width / window.height.toFloat() }

// Six vertices per particle, no index buffer.
m.set(cudaParticleCount * 6, 0) {}

m
