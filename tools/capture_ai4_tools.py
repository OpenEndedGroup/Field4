"""Run every example on ``public/docs/ai4_tools.md`` and regenerate the page's tool reference.

On a Field this script starts, in a temporary workspace, it opens one empty document and makes the
page's calls in the page's order, through ``agent-ai-tool-call`` — the same registry, gates and
handlers an AI4 chat uses, with no model in between. Each call on the page is written as

    <!-- ai4-call NewBoxRequest [keep=a,b] [image=name.png] [focus=Name] -->
    ```diffstatic {data-lang=json}
    { ...arguments... }
    ```

    ```diffstatic {data-lang=json}
    { ...result... }
    ```

The second fence must equal what the call returned, shaped the same way every time: only the
``keep`` keys if given, long strings and lists clipped, each box uid written ``<uid of Name>`` and
this run's workspace written ``<workspace>``. Arguments may use ``{{uid:Name}}`` for a box made earlier.
``focus=`` makes the call as a chat focused on that box would. ``image=`` saves the first picture the call returned to ``public/docs/assets/ai4_tools/``.

Between ``<!-- ai4-tools-reference:begin -->`` and ``<!-- ai4-tools-reference:end -->`` the page
carries the catalogue as ``agent-ai-tools-list`` reports it for a box-focused chat on that document,
less any tool whose class is declared under ``src/personal``.

``--update-page`` writes what the run produced into the page instead of failing on a difference.

Use: uv run --project . --group backdoor python tools/capture_ai4_tools.py [--port 9886] [--update-page]
"""

import argparse
import asyncio
import base64
import json
import re
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

from field_python_env import ensure_repo_python

ensure_repo_python()
import field_backdoor as fb  # noqa: E402

ROOT = fb.REPO_ROOT
PAGE = ROOT / "public/docs/ai4_tools.md"
ASSETS = ROOT / "public/docs/assets/ai4_tools"
PERSONAL = ROOT / "src/personal"
REF_BEGIN, REF_END = "<!-- ai4-tools-reference:begin -->", "<!-- ai4-tools-reference:end -->"
CALL = re.compile(
    r"<!-- ai4-call (?P<tool>\w+)(?P<opts>[^>]*?) -->\n"
    r"```diffstatic \{data-lang=json\}\n(?P<args>.*?)\n```\n\n"
    r"```diffstatic \{data-lang=json\}\n(?P<result>.*?)\n```",
    re.S,
)
MAX_STRING = 240
MAX_LIST = 4
# Different on every run and meaningless to a reader: dropped wherever they appear.
VOLATILE = {"requestId", "elapsedMs"}

# What each namespace is for, in the reader's words. A namespace the catalogue reports that is not
# here fails the run, so a new cluster of tools cannot reach the page without a sentence.
NAMESPACES = {
    "graph": "Boxes: make, read, change, connect, run, launch, stop, and look at their output.",
    "kotlin": "Compile and evaluate Kotlin in a box's own context without running the box.",
    "source": "A box's live, saved and last-run source, and edits to it.",
    "states": "The document's saved histories and the states in them.",
    "guides": "Markdown guides that live in the document and outlast the chat.",
    "memory": "The chat's own scratchpad and checklist.",
    "program": "One call that runs a short JavaScript program over the other tools.",
    "search": "Field's tutorials and the workspace's files, by what they do.",
    "symbols": "Declarations in Field and the workspace, with their KDoc.",
    "retrieval": "Natural-language search over Field's source, workspace code and docs.",
    "codeuse": "What box code has actually called, from Field's local telemetry.",
    "macros": "Saved operation-transcript macros: list, read, write, run, undo.",
    "render": "The native render window: capture it, reload its shaders.",
    "geometry": "Geometry views and review notes (see [Geometry review](geometry_review.md)).",
    "builds": "Long-running study builds (see [Let AI4 wait for long builds](ai4_long_builds.md)).",
    "kit": "The document's kit (see [Kits](kits.md)).",
    "core": "Housekeeping, and why a name a box reads resolves where it does.",
}


def personal_classes() -> set[str]:
    names: set[str] = set()
    for f in PERSONAL.rglob("*.kt"):
        names.update(re.findall(r"class\s+(\w+)", f.read_text(errors="replace")))
    return names


def first_sentence(text: str) -> str:
    text = " ".join((text or "").split())
    for m in re.finditer(r"[.!?](?=\s|$)", text):
        if not re.search(r"\b(e\.g|i\.e|etc|vs)\.$", text[: m.end()]):
            text = text[: m.end()]
            break
    return text.replace("|", "\\|")


def reference(tools: list[dict]) -> str:
    by_ns: dict[str, list[dict]] = defaultdict(list)
    for t in tools:
        by_ns[t["x-field"]["namespace"]].append(t)
    unknown = sorted(set(by_ns) - set(NAMESPACES))
    assert not unknown, f"namespaces with no sentence in NAMESPACES: {unknown}"
    out = [f"{len(tools)} tools in {len(by_ns)} namespaces.", ""]
    for ns in NAMESPACES:
        if ns not in by_ns:
            continue
        out += [f"### `{ns}`", "", NAMESPACES[ns], "", "| Tool | What it does | Effects |", "| --- | --- | --- |"]
        for t in sorted(by_ns[ns], key=lambda t: t["name"]):
            x = t["x-field"]
            name = f"`{t['name']}`" + (" \\*" if x["exposure"] == "DEFERRED" else "")
            effects = ", ".join(e.lower().replace("_", " ") for e in x["effects"] if e != "UNDECLARED") or "—"
            out.append(f"| {name} | {first_sentence(t.get('description', ''))} | {effects} |")
        out.append("")
    return "\n".join(out).rstrip()


class Shaper:
    def __init__(self, workspace: Path) -> None:
        self.workspace = str(workspace.resolve())
        self.workspace_alias = str(workspace)
        self.uids: dict[str, str] = {}  # name -> uid

    def text(self, s: str) -> str:
        paths = {self.workspace, self.workspace_alias, "/private" + self.workspace_alias, self.workspace.removeprefix("/private")}
        for path in sorted(paths, key=len, reverse=True):
            s = s.replace(path, "<workspace>")
        for name, uid in self.uids.items():
            s = s.replace(uid, f"<uid of {name}>")
        return s

    def value(self, v):
        if isinstance(v, str):
            v = self.text(v)
            return v if len(v) <= MAX_STRING else v[:MAX_STRING] + f"… ({len(v) - MAX_STRING} more characters)"
        if isinstance(v, list):
            shown = [self.value(x) for x in v[:MAX_LIST]]
            return shown + ([f"… {len(v) - MAX_LIST} more"] if len(v) > MAX_LIST else [])
        if isinstance(v, dict):
            # Fields a result type always carries but this call left empty say nothing to a reader.
            return {k: self.value(x) for k, x in v.items() if x not in (None, "", [], {}) and k not in VOLATILE}
        return v

    def args(self, raw: str) -> dict:
        text = re.sub(r"\{\{uid:([^}]+)\}\}", lambda m: self.uids[m.group(1)], raw)
        return json.loads(text)


async def capture(port: int, update_page: bool) -> None:
    page = PAGE.read_text()
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ai4-tools-capture-") as temp:
        workspace = Path(temp) / "workspace"
        workspace.mkdir()
        shaper = Shaper(workspace)
        log = fb.SessionLogger(fb.LOG_ROOT, ["ai4-tools-docs-capture"])
        c = fb.FieldBackdoor(port=port, workspace=str(workspace), logger=log, default_timeout=120)
        new_page, failures, pos = [], [], 0
        try:
            await c.ensure_server()
            assert c.managed_process is not None, "Use a free port; the capture must own its server"
            await c.connect()
            await c.open_document("ai4-tools", scratch=False, keep_running=True, timeout=60)
            await c.open_playwright(timeout=90)
            for m in CALL.finditer(page):
                tool, opts = m.group("tool"), dict(o.split("=", 1) for o in m.group("opts").split())
                focus = shaper.uids[opts["focus"]] if "focus" in opts else None
                reply = await c.ai_tool_call(name=tool, arguments=shaper.args(m.group("args")), timeout=180, focus_box=focus)
                assert reply.get("ok"), f"{tool}: {json.dumps(reply)[:1500]}"
                result = reply["result"]
                if tool == "NewBoxRequest":
                    shaper.uids[json.loads(m.group("args"))["name"]] = result["uid"]
                if "image" in opts:
                    images = reply.get("images") or []
                    assert images, f"{tool} returned no picture for {opts['image']}"
                    data = images[0]["dataUrl"].split(",", 1)[1]
                    (ASSETS / opts["image"]).write_bytes(base64.b64decode(data))
                    assert f"assets/ai4_tools/{opts['image']}" in page, f"page never shows {opts['image']}"
                if "keep" in opts and isinstance(result, dict):
                    result = {k: result[k] for k in opts["keep"].split(",") if k in result}
                shown = json.dumps(shaper.value(result), indent=2, ensure_ascii=False)
                print(f"== {tool}\n{shown}\n", flush=True)
                if shown != m.group("result"):
                    failures.append(tool)
                new_page += [page[pos:m.start("result")], shown]
                pos = m.end("result")
            new_page.append(page[pos:])
            catalogue = await c.ai_tools_list(timeout=60, focus_box=next(iter(shaper.uids.values()), None))
        finally:
            await c.close()
    personal = personal_classes()
    catalogue = [t for t in catalogue if t["name"] not in personal]
    text = "".join(new_page)
    begin, end = text.index(REF_BEGIN) + len(REF_BEGIN), text.index(REF_END)
    table = "\n" + reference(catalogue) + "\n"
    if text[begin:end] != table:
        failures.append("reference table")
    text = text[:begin] + table + text[end:]
    if update_page:
        PAGE.write_text(text)
        print(f"wrote {PAGE.relative_to(ROOT)}")
    elif failures:
        sys.exit(f"page differs from this run for: {', '.join(failures)} (re-run with --update-page)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=9886)
    parser.add_argument("--update-page", action="store_true")
    args = parser.parse_args()
    asyncio.run(capture(args.port, args.update_page))


if __name__ == "__main__":
    main()
