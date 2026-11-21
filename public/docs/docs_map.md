# Map of the docs

Every page on this site, the [agent transcripts](agent_transcripts.md) they open, and the source
files their code calls, drawn as one graph you can turn around. Pages sit near the pages they link
to. Source files sit on a shell outside, each one toward the pages that use it, grouped by the
directory it lives in.

Drag to turn the map, right-drag to pan and scroll to zoom. Click a node to list what it links to
and what links to it, then follow its title to open it: a page opens itself, and a source file
opens its section of the [reference](reference/index.md). Double-click a node to frame it with
everything it connects to. Switch on **cites file**, **calls (fences)** or **mentions (prose)**
and the source files move in beside the pages that use them.

<iframe
  src="assets/docs_map/graph.html"
  title="Map of the docs"
  style="width:100%; height:80vh; min-height:520px; border:1px solid #8884; border-radius:8px;"
  loading="lazy">
</iframe>

<a href="assets/docs_map/graph.html" target="_blank">Open the map in its own window</a>

The map is rebuilt with the site, so it always matches the pages you are reading.

## Where to go next

- [What's already in the box](reference/index.md) lists the library by area, with the
  documentation that sits beside each source file.
- [The agent transcript gallery](agent_transcripts.md) opens the documents the transcripts build.
- [What's new](changelog.md) lists the pages added most recently.
