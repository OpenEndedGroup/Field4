# Viewer display, export and optional tools

The standard viewer supports X-ray and portable GLB downloads for ordinary mesh, line
and point scenes. Use `FieldInspector.xray(enabled, opacity)` in the capture browser;
review snapshots retain these settings. X-ray changes rendering only: picking still
stops at the nearest visible surface. Hide or section a surface to pick behind it.

The toolbar and `await FieldViewer.exportGLB({download:false})` share the exporter.
The latter returns an ArrayBuffer. Export retains visible geometry, active draw ranges,
current transforms, colors and alpha. Section cuts are baked without caps. Known units
convert to metres. Helpers and annotation text are excluded. Geometry and material
copies are detached and disposed after export, leaving the live view unchanged.

Use the actual blob download path for large assets; sending a binary asset through
page-eval JSON or a traced network upload can exceed the browser driver's string limit.
Return compact metadata to the agent instead.

Tool implementations belong to their application. The generic host provides explicit
same-origin module loading, registration, toolbar and overlay ownership, saved state,
refresh and disposal. There is no built-in project layout tool. The public guide's
"Optional viewer tools" section and `threejs_tools_demo.js` demonstrate the contract.
A note can reference an installed tool's state but cannot import code by itself.

Run `three-xray`, `three-glb`, `three-tools` and `three-feature-review` with
`tools/run_ui_regressions.py`. Their neutral geometry fixtures cover rendered pixels,
nearest-surface selection, saved evidence, material export, state restoration and zero
idle frames. Run `tools/test_field_inspection.py` for agent argument handling and
`tools/check_offline_assets.py` for the vendored exporter and modules.
