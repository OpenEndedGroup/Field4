# d3-force-3d-webgpu 3.2.0

[d3-force-3d](https://github.com/vasturiano/d3-force-3d) with every force also running as a WebGPU
compute shader, including its octree Barnes–Hut many-body force, and an automatic CPU fallback:
<https://github.com/erw-1/d3-force-3d-webgpu> (MIT, `LICENSE`).

`d3-force-3d-webgpu.js` is the package's unminified UMD build (`dist/`), unmodified. `deps/` holds
the UMD builds of the five d3 modules it reads from the `d3` global, each with its licence:
d3-dispatch 3.0.2, d3-quadtree 3.0.2, d3-timer 3.0.2 (ISC), d3-binarytree 1.0.2 and d3-octree 1.1.0
(MIT). Load the deps first.

Used by `tools/docs_link_graph.html` (the docs site's Map of the Docs), which inlines these files.
