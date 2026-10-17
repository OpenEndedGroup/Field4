# Make meshFromFile throw on unparseable contents instead of returning an empty mesh

- **Handoff:** `f4f48d69-6bd3-4301-928b-c3f16e1dc085` (project)
- **Filed from:** https://openendedgroup.com/field4/geometry_from_disk/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close f4f48d69 "what happened"`

geometry_from_disk.md says:

> The first row is the easiest to lose time on: a file called `thing.obj` that is actually a ZIP, a glTF, or an HTML error page parses to zero vertices and zero triangles and raises nothing. `meshFromFile` throws for a *missing* file and for an extension it does not read, but not for a file whose contents make no sense.

Fix in src/jvmMain/kotlin/afield/geometry/mesh/mesh_io.kt (and meshFromAnyFile): sniff magic bytes / throw when a non-empty file parses to zero vertices. Update the troubleshooting table's first row and delete this paragraph in the same change.
