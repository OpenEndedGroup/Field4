# Map of the docs

Every page on this site, the [agent transcripts](agent_transcripts.md) they open, and the source
files their code calls, drawn as one graph you can turn around. The map lays itself out as you
watch: pages settle near the pages they link to, and source files cluster with the others in their
directory. **flat (2D)** lays it out in the plane instead.

Drag to turn the map, right-drag to pan and scroll to zoom. Click a node to list what it links to
and what links to it, then follow its title to open it: a page opens itself, and a source file
opens its section of the [reference](reference/index.md). Clicking a node also gathers what it
connects to around it, and double-clicking frames it. Switch on **cites file**, **calls (fences)**
or **mentions (prose)** and the source files move in beside the pages that use them. The map icon at
the right of every page's title opens the map at that page.

<iframe
  src="assets/docs_map/graph.html"
  title="Map of the docs"
  class="docs-map-frame"
  loading="lazy">
</iframe>

<a id="docs-map-window" href="assets/docs_map/graph.html" target="_blank">Open the map in its own window</a>

<script>
// A page's map icon opens this one with ?node=<that page>; the map above
// reads it from here, and the full-window map gets it too.
(() => { const a = document.getElementById("docs-map-window"); if (a && location.search) a.href = a.getAttribute("href") + location.search; })();
</script>

The map is rebuilt with the site, so it always matches the pages you are reading.

## Where to go next

- [What's already in the box](reference/index.md) lists the library by area, with the
  documentation that sits beside each source file.
- [The agent transcript gallery](agent_transcripts.md) opens the documents the transcripts build.
- [What's new](changelog.md) lists the pages added most recently.
