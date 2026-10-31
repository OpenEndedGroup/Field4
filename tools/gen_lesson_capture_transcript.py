"""Write a docs-capture transcript that rebuilds a chain of canonical_graphics lessons from their sidecars.

argv: transcript name, description, then lesson uids parent-first (the `window` renderer first).
Every box gets its code and any shader sidecars verbatim, is connected beneath the previous one,
and is launched in order. Run from a checkout root; writes dev_docs/agents/transcripts/docs_capture/<name>.yaml.
"""
import sys
from pathlib import Path

LESSONS = Path("public/docs/assets/canonical_graphics")
SHADERS = ("vertex", "fragment", "compute")


def block(text: str, indent: int) -> str:
    pad = " " * indent
    return "\n".join(pad + line if line else "" for line in text.rstrip("\n").split("\n"))


args = sys.argv[1:]
camera_at = None
window = None
while args[0].startswith("--"):
    flag, value = args.pop(0).split("=", 1)
    if flag == "--camera-at":
        camera_at = value
    elif flag == "--window":
        window = value
name, description, uids = args[0], args[1], args[2:]
names = {}
for line in (LESSONS / "LESSONS.md").read_text().splitlines():
    if line.startswith("| ") and "_code_.kt" in line:
        cells = [c.strip() for c in line.strip("|").split("|")]
        names[cells[2].strip("`").split("_")[0]] = cells[0]
names.setdefault(uids[0], "window")

out = [f"name: {name}", "description: >", f"  {description}", "docs:", "  - page: afield_four_graphics.md",
       '    why: "Rebuilds the lesson chain this page captures, from the canonical_graphics sidecars."', "operations:"]
for i, uid in enumerate(uids):
    out += ["  - create:", f"      box: {name}_{i}", f"      name: {names.get(uid, uid)!r}",
            f"      frame: {{ x: 40.0, y: {40.0 + 160.0 * i}, w: 300.0, h: 100.0 }}", "      properties:"]
    code = (LESSONS / f"{uid}_code_.kt").read_text()
    marker = "val camera = Camera()\n"
    if camera_at and marker in code:
        # Closer than Camera's default (0, 0, -10), so the lesson's quad fills the docs capture.
        code = code.replace(marker, marker + f"camera.at = vec({camera_at})  // closer, for the docs capture\n", 1)
    if window and 'Window("Field")' in code:
        # A smaller window for a video: each frame copy is the swapchain's size, and copying a
        # screen-sized one is slow enough to throttle the renderer.
        code = code.replace('Window("Field")', f'Window("Field", initialDimensions = Rect({window}))', 1)
    out += ["        code: |", block(code, 10)]
    for prop in SHADERS:
        f = LESSONS / f"{uid}_{prop}_.glsl"
        if f.exists():
            out += [f"        {prop}: |", block(f.read_text(), 10)]
for i in range(1, len(uids)):
    out.append(f"  - connect: {{ parent: {name}_{i - 1}, child: {name}_{i} }}")
out.append(f"  - launch: {{ box: {name}_0, property: code, strand: renderer }}")
out.append("  - await: {}")
for i in range(1, len(uids)):
    out += [f"  - launch: {{ box: {name}_{i}, property: code }}", "  - await: {}"]
target = Path("dev_docs/agents/transcripts/docs_capture") / f"{name}.yaml"
target.write_text("\n".join(out) + "\n")
print("wrote", target)
