# Vendored web runtime

Field Remote loads its rendering and audio dependencies locally so the head can boot on a device without relying on a CDN.

| Package | Version | Role |
| --- | --- | --- |
| `aframe` | 1.8.0 | Browser bundle and the authoritative embedded Three.js runtime. |
| `super-three` | 0.184.0 | Matching Three.js module build for tests, tooling, and future module migration. A-Frame's browser bundle already contains this code. |
| `tone` | 15.1.22 | Audio runtime used by the advanced RemoteJS examples. |

Each package directory contains its upstream license, README, and package metadata. The exact files and archive checksums are declared in `tools/vendor_remote_ar_runtime.py`.

Upstream `dist/` and `build/` files are flattened into the version directory. This avoids the repository-wide `**/build/` ignore rule and keeps the browser URLs stable.

Do not patch the minified A-Frame bundle in place. Put Field-owned integration in `ar/` or `field/`; if an upstream patch becomes unavoidable, keep the unminified bundle, document the patch here, and regenerate the minified artifact reproducibly.
