# Fabricate Demo Assets

`ToyCar.glb`

- Source: Khronos glTF Sample Assets, `Models/ToyCar/glTF-Binary/ToyCar.glb`
- License: CC0 1.0 Universal
- Credits from upstream metadata: Guido Odendahl for the initial car model; Eric Chadwick for extensions and scene composition
- Why it is here: compact real glTF sample with transmission, clearcoat, sheen, texture transforms, and multiple texture semantics

`venice_sunset_1k.exr`

- Source: Poly Haven, Venice Sunset HDRI
- License: CC0
- Author from Poly Haven metadata: Greg Zaal
- Why it is here: source HDRI for Fabricate's three.js-style PBR/IBL path
- Local encoding: tiled ZIP RGB32F EXR, re-encoded from the original PIZ scanline download so LWJGL TinyEXR can decode it directly

`venice_sunset_1k.png`

- Derived from: `venice_sunset_1k.exr`
- License: CC0
- Why it is here: 8-bit equirectangular companion for quick previews and fallback debugging; the renderer's primary IBL path now loads the EXR as RGBA32F

`../fabricate-toy-car-donmccurdy-reference.png`

- Derived from: local render of `ToyCar.glb` and `venice_sunset_1k.exr`
- Viewer: Don McCurdy's glTF Viewer, local temporary clone of `donmccurdy/three-gltf-viewer`
- Viewer license: MIT
- Why it is here: visual reference for Field's Fabricate ToyCar renderer capture

`../fabricate-toy-car-donmccurdy-reference-close.png`

- Derived from: center crop of `fabricate-toy-car-donmccurdy-reference.png`, resized back to the original canvas
- Viewer license: MIT
- Why it is here: temporary close framing for paint/roughness comparison until the reference viewer is recaptured with a matching camera

`../fabricate-toy-car-close-comparison.png`

- Derived from: `fabricate-toy-car-donmccurdy-reference-close.png` and Field's native `fabricate-toy-car-gltf.png` capture
- Why it is here: quick side-by-side diagnostic for the current ToyCar material/IBL pass
