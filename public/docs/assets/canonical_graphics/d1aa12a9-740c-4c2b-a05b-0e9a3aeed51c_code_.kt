prop<RenderToTexture>(b0) = target

prop<RenderToTexture>(b1) = RenderToTexture {
    clearFirst = true
    useDepth = false
    mipLevels = 10
    colorLayerCount = 2
    colorAttachments = 2
}

renderer.renderList += "b1" to b1

var module = moduleHere { 
    primitiveType = triangle_strip
    depth = 0
    b1.apply()
}

var m = Mesh()
module += "mainMesh" to m
b1 += "boo" to module

m.radius = { 10.0f }
m.sigma = { 10.0f }
m.resize(4)
