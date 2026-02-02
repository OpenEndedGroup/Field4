# PLY File Loader for afield.four

## Overview

This is a self-contained PLY (Polygon File Format) loader for use in afield.four. It provides comprehensive support for loading 3D models and point clouds in both ASCII and binary PLY formats.

## Features

- ✅ **Binary and ASCII Support**: Handles both binary (little-endian and big-endian) and ASCII PLY formats
- ✅ **Position Data**: Recognizes and parses x, y, z vertex positions
- ✅ **Color Data**: Recognizes and parses red, green, blue, alpha color channels (supports both 0-255 and 0.0-1.0 ranges)
- ✅ **Normal Data**: Recognizes and parses nx, ny, nz normal vectors
- ✅ **Point Cloud Topology**: Supports vertex-only data (point clouds without faces)
- ✅ **Triangle Topology**: Supports face lists with automatic triangulation of polygons
- ✅ **Flexible Format**: Handles various property orderings and optional properties

## PLY Format Support

### Supported Elements
- `vertex`: Required element containing vertex data
- `face`: Optional element containing face connectivity (triangles/polygons)

### Supported Properties

#### Vertex Properties
| Property | Type | Required | Description |
|----------|------|----------|-------------|
| x, y, z | float/double | ✅ | Vertex positions |
| nx, ny, nz | float/double | ❌ | Vertex normals (defaults to 0,0,1) |
| red, green, blue | uchar/float | ❌ | Vertex colors (defaults to white) |
| alpha | uchar/float | ❌ | Vertex alpha (defaults to 1.0) |

#### Face Properties
| Property | Type | Description |
|----------|------|-------------|
| vertex_indices | list of int/uint | Vertex indices forming the face |

### Supported Data Types
- `char`, `int8`: 8-bit signed integer
- `uchar`, `uint8`: 8-bit unsigned integer
- `short`, `int16`: 16-bit signed integer
- `ushort`, `uint16`: 16-bit unsigned integer
- `int`, `int32`: 32-bit signed integer
- `uint`, `uint32`: 32-bit unsigned integer
- `float`, `float32`: 32-bit floating point
- `double`, `float64`: 64-bit floating point

## Usage

### Basic Usage

```kotlin
import afield.four.modules.PlySupport
import afield.four.modules.Mesh
import afield.four.util.Camera

// Load a PLY file (automatically detects ASCII vs binary)
val model = PlySupport.loadPly("path/to/model.ply")

// Apply to a mesh for rendering
val mesh = Mesh(renderer, module.pointer())
val camera = Camera()

model.applyToMesh(mesh, camera)
mesh.draw(module, commandBuffer, module.pushConstantBuffer)
```

### Using the Macro (Box API)

```kotlin
// In a Field box:
val (module, model) = plyModelHere("model.ply") {
    // Optional parameters configuration
}
```

### Point Cloud Example

```kotlin
// Load a point cloud (PLY file without faces)
val pointCloud = PlySupport.loadPly("pointcloud.ply")

// The loader automatically detects point clouds
println("Is point cloud: ${pointCloud.buffers.isPointCloud}")

// Apply and render (same API as triangle meshes)
pointCloud.applyToMesh(mesh, camera)
```

### Triangle Mesh Example

```kotlin
// Load a triangle mesh (PLY file with faces)
val triangleMesh = PlySupport.loadPly("mesh.ply")

// Supports both triangles and n-gons (automatically triangulated)
println("Vertex count: ${triangleMesh.buffers.vertexCount}")
println("Index count: ${triangleMesh.buffers.indexCount}")

triangleMesh.applyToMesh(mesh, camera)
```

## Example PLY Files

### ASCII Format Triangle

```ply
ply
format ascii 1.0
comment Simple colored square
element vertex 4
property float x
property float y
property float z
property float nx
property float ny
property float nz
property uchar red
property uchar green
property uchar blue
element face 2
property list uchar int vertex_indices
end_header
0.0 0.0 0.0 0.0 0.0 1.0 255 0 0
1.0 0.0 0.0 0.0 0.0 1.0 0 255 0
1.0 1.0 0.0 0.0 0.0 1.0 0 0 255
0.0 1.0 0.0 0.0 0.0 1.0 255 255 0
3 0 1 2
3 0 2 3
```

### ASCII Format Point Cloud

```ply
ply
format ascii 1.0
comment Point cloud with colors
element vertex 3
property float x
property float y
property float z
property uchar red
property uchar green
property uchar blue
end_header
0.0 0.0 0.0 255 0 0
1.0 0.0 0.0 0 255 0
0.5 1.0 0.0 0 0 255
```

### Binary Format

```ply
ply
format binary_little_endian 1.0
element vertex 8
property float x
property float y
property float z
property uchar red
property uchar green
property uchar blue
element face 12
property list uchar int vertex_indices
end_header
[binary data follows]
```

## Data Structure

The loader provides these data structures:

### PlyMeshBuffers
Contains the loaded geometry data:
- `vertexData: ByteBuffer` - Packed vertex data [pos.xyz][normal.xyz][color.rgba]
- `indexData: IntBuffer?` - Triangle indices (null for point clouds)
- `vertexCount: Int` - Number of vertices
- `indexCount: Int` - Number of indices
- `hasColors: Boolean` - Whether color data is present
- `hasNormals: Boolean` - Whether normal data is present
- `isPointCloud: Boolean` - Whether this is a point cloud (no faces)

### PlyModel
High-level model representation:
- `buffers: PlyMeshBuffers` - The mesh data
- `name: String` - Model name (filename)
- `applyToMesh(mesh, camera)` - Apply to a Mesh for rendering

## Vertex Layout

The loader packs vertex data in a fixed layout:
```
[position.xyz] [normal.xyz] [color.rgba] = 10 floats per vertex
```

This layout is compatible with the provided GLSL shaders.

## Shaders

The loader includes default GLSL shaders:
- `PlySupport.plyVertexShader` - Vertex shader with SSBO vertex pulling
- `PlySupport.plyFragmentShader` - Fragment shader with color and lighting

These shaders use:
- Buffer references for vertex/index data (Vulkan extension)
- Push constants for transformation matrices
- Uniform buffer for additional parameters

## Notes

### Polygon Triangulation
- Triangles are passed through directly
- Quads and n-gons are triangulated using a fan triangulation
- All faces are converted to triangles internally

### Color Handling
- Colors in range 0-255 (uchar) are automatically normalized to 0.0-1.0
- Colors already in range 0.0-1.0 (float) are passed through
- Missing colors default to white (1.0, 1.0, 1.0, 1.0)

### Normal Handling
- Missing normals default to (0, 0, 1)
- Normals are not automatically computed for faces

### Performance
- Binary formats are faster to parse than ASCII
- Large files are handled efficiently with direct byte buffer access
- Vertex data is tightly packed for GPU transfer

## Error Handling

The loader provides clear error messages for:
- Missing required properties (x, y, z)
- Invalid PLY format
- Unsupported property types
- File not found
- Malformed headers

## Limitations

- Does not support embedded textures
- Does not support vertex texture coordinates (UVs) - can be added if needed
- Does not support skeletal animation data
- List properties in vertex elements are skipped

## Future Enhancements

Potential additions if needed:
- Texture coordinate (UV) support
- Edge and material data
- Automatic normal generation for faces
- Mesh optimization (duplicate vertex removal)
- Level-of-detail (LOD) generation

## References

- [PLY File Format Specification](http://paulbourke.net/dataformats/ply/)
- [Stanford 3D Scanning Repository](http://graphics.stanford.edu/data/3Dscanrep/)
