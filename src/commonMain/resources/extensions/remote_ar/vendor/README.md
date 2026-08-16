# Vendored web runtime

Field Remote loads its rendering and audio dependencies locally so the head can boot on a device without relying on a CDN.

| Package | Version | Role |
| --- | --- | --- |
| `aframe` | 1.8.0 | Browser bundle and the authoritative embedded Three.js runtime. |
| `tone` | 15.1.22 | Audio runtime used by the advanced RemoteJS examples. |

`super-three` 0.184.0 used to sit between those two, described as a module build kept for "tests,
tooling, and future module migration". It was never any of those things: only `three.module.js` was
vendored, and since r16x that file imports a `three.core.js` that the spec never listed, so nothing
could load it. It is gone. A-Frame's bundle embeds that fork itself, and the one Three.js anybody
imports directly is the stock build under `src/commonMain/resources/vendor/three/`, which the docs
viewer uses and which ships both halves.

Each package directory contains its upstream license, README, and package metadata. The exact files and archive checksums are declared in `tools/vendor_remote_ar_runtime.py`.

Upstream `dist/` and `build/` files are flattened into the version directory. This avoids the repository-wide `**/build/` ignore rule and keeps the browser URLs stable.

Do not patch the minified A-Frame bundle in place. Put Field-owned integration in `ar/` or `field/`; if an upstream patch becomes unavoidable, keep the unminified bundle, document the patch here, and regenerate the minified artifact reproducibly.
