#!/usr/bin/env python3
"""Read tracked teaching code; write a review inventory without changing its sources.

Run with the repo's backdoor dependencies. The inventory includes code occurrences,
not an assertion that every docs fence is a complete, executable box. Exact content
matches are navigation aids, never proof that two independently authored examples
share an owner. EDN structure uses the existing lossless refactoring reader.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

import yaml
from yaml.nodes import MappingNode, ScalarNode, SequenceNode

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.refactoring.containers import EdnSpans

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = {"code": "kotlin", "preamble": "kotlin", "introduction": "kotlin",
             "customization": "javascript", "stackSL": "stacksl", "rectSL": "rectsl",
             "msl": "metal", "vertex_fragment": "slang"}
LANGUAGES.update(dict.fromkeys(("vertex", "fragment", "compute", "task", "mesh", "raygen",
                               "miss", "closesthit", "anyhit", "intersection", "callable"), "glsl"))
LANGUAGES.update({p + "_s": "slang" for p in list(LANGUAGES) if LANGUAGES[p] == "glsl"})
EXTENSIONS = {"kt": "kotlin", "kts": "kotlin", "glsl": "glsl", "slang": "slang",
              "metal": "metal", "stacksl": "stacksl", "rectsl": "rectsl", "js": "javascript"}
EXTENSIONS.update(dict.fromkeys(("vert", "frag", "comp", "geom", "tesc", "tese",
                                 "rgen", "rchit", "rmiss", "rahit", "rint", "rcall"), "glsl"))
ALIASES = {"kt": "kotlin", "javascript": "javascript", "js": "javascript", "remotejs": "javascript",
           "kotlinjs": "kotlinjs", "kotlin/js": "kotlinjs", "py": "python"}
PUBLISHED = ("dev_docs/agents/transcripts/", "dev_docs/agents/demos/", "public/docs/", "templates/")
GENERATED = "public/docs/assets/agent_transcripts/"


def language(value):
    value = str(value).lower()
    return ALIASES.get(value, value)


def digest(code):
    return hashlib.sha256(code.replace("\r\n", "\n").rstrip("\n").encode()).hexdigest()


def historical(path):
    # The function walkthrough packages these exact bytes under recorded revision/hash IDs.
    # Its live starter is separate; the recorded example is provenance, not editable box source.
    if path.startswith("dev_docs/agents/demos/function_conversation_example/"):
        return True
    if "/studies/" in path and re.match(r"r\d+-[0-9a-f]+\.", Path(path).name):
        return True
    return any(p in {"backups", "backup", ".backups"} for p in Path(path).parts) or "/ai4_chat_archive/chats/" in path or Path(path).name in {"operations.yaml", "versions.edn"}


class Inventory:
    def __init__(self, root, tracked):
        self.root, self.tracked = root, set(tracked)
        self.rows, self.findings, self.excluded = [], [], []
        self.sidecars = {}
        self.file_digests = {}

    def add(self, path, locator, code, prop="code", box="", lang=None, line=1, role="source", **extra):
        if not isinstance(code, str) or not code.strip():
            return
        if (lang or LANGUAGES.get(prop)) == "kotlin" and re.match(r"\s*//\s*\{\{\s*(?:remoteAR|remote|remotear|remote-ar|ar|tone|tonejs|music|visionmetal|vision-metal|metal)\b", code, re.I):
            lang = "kotlinjs"
        if path.startswith(GENERATED):
            role = "generated"
            extra["generated_from"] = "dev_docs/agents/transcripts/" + path[len(GENERATED):].split("/")[0] + ".yaml"
        elif historical(path):
            role = "history"
        group = path
        if path.startswith("dev_docs/agents/demos/"):
            group = "/".join(path.split("/")[:4])
        elif path.startswith("templates/") and len(Path(path).parts) > 2:
            group = "/".join(path.split("/")[:2])
        else:
            for parent in Path(path).parents:
                if str(parent / "metadata.edn") in self.tracked or str(parent / "metadata.json") in self.tracked:
                    group = parent.as_posix()
                    break
        flags = []
        if re.search(r"^\s*//\s*%-", code, re.M):
            flags.append("preserve-fragments")
        if re.search(r"\b(?:frames|animate|loop|start|launch)\s*\{", code):
            flags.append("check-lifecycle-and-live-edits")
        if re.search(r"\b(?:fromContext|renderer|moduleHere|computeModuleHere)\b", code):
            flags.append("needs-graph-context")
        if re.search(r"\b(?:setPropertyRaw|getPropertyRaw)\s*(?:<[^>]*>)?\s*\(", code):
            flags.append("review-raw-property-access")
        if path not in self.file_digests:
            self.file_digests[path] = hashlib.sha256((self.root / path).read_bytes()).hexdigest()
        self.rows.append(dict(id=path + "#" + locator, path=path, locator=locator, line=line,
                              box=str(box), property=prop, language=language(lang or LANGUAGES.get(prop, "unknown")),
                              role=role, group=group, sha256=digest(code), source_sha256=self.file_digests[path], lines=len(code.splitlines()),
                              fragment_breaks=len(re.findall(r"^\s*//\s*%-", code, re.M)), flags=flags, **extra))

    def document(self, path):
        text = (self.root / path).read_text()
        if path.endswith(".json"):
            boxes = json.loads(text)
            if isinstance(boxes, dict):
                boxes = boxes.get("boxes", [])
            records = []
            for box in boxes:
                props = box.get("properties", {})
                values = {k: v.get("second") if isinstance(v, dict) and "first" in v else v for k, v in props.items()}
                records.append((str(box.get("id", "")), values, props, None))
        else:
            tree = EdnSpans(text).document()
            boxes = tree.mapping().get("boxes") if tree.kind == "map" else tree
            if boxes is None:
                return
            records = []
            for box in boxes.children:
                fields = box.mapping()
                props = fields["properties"].mapping()
                records.append((fields.get("id").value, {k: v.value for k, v in props.items()}, props, box))
        declared_languages = {}
        parents = {}
        for box, values, props, node in records:
            declared = values.get("code_language") or values.get("mainLanguage")
            if declared:
                declared_languages[box] = declared
            if path.endswith(".json"):
                parents[box] = [str(p) for p in values.get("parents", [])]
            else:
                parent_node = props.get("parents")
                parents[box] = [p.children[0].value if p.kind == "tag" and p.children else p.value
                                for p in parent_node.children] if parent_node else []

        def inherited_language(box, seen=None):
            seen = set() if seen is None else seen
            if box in seen:
                return None
            seen.add(box)
            if box in declared_languages:
                return declared_languages[box]
            for parent in parents.get(box, []):
                found = inherited_language(parent, seen)
                if found:
                    return found
            return None

        for box, values, props, node in records:
            declared = inherited_language(box) or "kotlin"
            for prop in LANGUAGES:
                if prop not in props:
                    continue
                item, sidecar, code = props[prop], None, None
                lang = declared if prop == "code" else LANGUAGES[prop]
                line = 1
                if path.endswith(".json"):
                    if isinstance(item, dict) and item.get("first") == "__file__":
                        sidecar = item["second"]
                    else:
                        code = values[prop]
                else:
                    line = text.count("\n", 0, item.start) + 1
                    if item.kind == "string":
                        code = item.value
                    elif item.kind == "tag" and item.value == "afield/file":
                        sidecar = item.children[0].value
                    elif item.kind == "tag" and item.value == "afield/file-inline":
                        payload = item.children[0].mapping()
                        sidecar, code = payload["path"].value, payload["inline"].value
                tombstone = values.get("_tombstone") in ("true", True)
                extra = dict(name=values.get("name", box), tombstone=tombstone)
                if sidecar:
                    target = (Path(path).parent / sidecar).as_posix()
                    self.sidecars[target] = dict(box=box, prop=prop, lang=lang, **extra)
                    if target not in self.tracked:
                        self.findings.append(dict(path=path, box=box, kind="inline-only-source" if code is not None else "missing-sidecar", target=target))
                    elif code is not None:
                        actual = (self.root / target).read_text()
                        if digest(actual) != digest(code):
                            self.findings.append(dict(path=path, box=box, kind="inline-sidecar-drift", target=target))
                    if code is not None:
                        self.add(path, box + "/" + prop, code, prop, box, lang, line,
                                 role="inline-fallback" if target in self.tracked else "source", owner=target if target in self.tracked else path, **extra)
                elif isinstance(code, str):
                    self.add(path, box + "/" + prop, code, prop, box, lang, line,
                             role="history" if tombstone else "source", **extra)

    def transcript(self, path, text=None, prefix="yaml", base_line=0, role="source"):
        text = (self.root / path).read_text() if text is None else text
        root = yaml.compose(text, Loader=yaml.SafeLoader)
        visited = set()
        box_languages = {}
        box_parents = defaultdict(list)
        collected = set()

        def collect(node):
            if node is None or id(node) in collected:
                return
            collected.add(id(node))
            if isinstance(node, MappingNode):
                pairs = {k.value: v for k, v in node.value if isinstance(k, ScalarNode)}
                box, props = pairs.get("box"), pairs.get("properties")
                if isinstance(box, ScalarNode) and isinstance(props, MappingNode):
                    values = {k.value: v.value for k, v in props.value if isinstance(v, ScalarNode)}
                    declared = values.get("code_language") or values.get("mainLanguage")
                    if declared:
                        box_languages[box.value] = declared
                parent, child = pairs.get("parent"), pairs.get("child")
                if isinstance(parent, ScalarNode) and isinstance(child, ScalarNode):
                    box_parents[child.value].append(parent.value)
                for _, value in node.value:
                    collect(value)
            elif isinstance(node, SequenceNode):
                for child in node.value:
                    collect(child)
        collect(root)

        def inherited_box_language(box, seen=None):
            seen = set() if seen is None else seen
            if box in seen:
                return None
            seen.add(box)
            if box in box_languages:
                return box_languages[box]
            for parent in box_parents.get(box, []):
                found = inherited_box_language(parent, seen)
                if found:
                    return found
            return None

        def walk(node, locator="yaml", box="", prop="code", lang="kotlin"):
            if node is None or id(node) in visited:
                return
            visited.add(id(node))
            if isinstance(node, MappingNode):
                pairs = {k.value: v for k, v in node.value if isinstance(k, ScalarNode)}
                def scalar(key, fallback):
                    value = pairs.get(key)
                    return value.value if isinstance(value, ScalarNode) else fallback
                box = scalar("box", box)
                prop = scalar("property", scalar("prop", prop))
                lang = scalar("code_language", scalar("language", inherited_box_language(box) or lang))
                properties = pairs.get("properties")
                if isinstance(properties, MappingNode):
                    lang = next((v.value for k, v in properties.value if k.value == "code_language"), lang)
                for key, value in pairs.items():
                    effective = prop if key in {"code", "value", "from", "to"} and "property" in pairs else key
                    if effective in LANGUAGES and isinstance(value, ScalarNode) and value.tag == "tag:yaml.org,2002:str":
                        self.add(path, locator + "/" + key, value.value, effective, box,
                                 lang if effective == "code" else LANGUAGES[effective], base_line + value.start_mark.line + 1,
                                 role=role, operation=locator)
                    else:
                        walk(value, locator + "/" + key, box, prop, lang)
            elif isinstance(node, SequenceNode):
                for i, child in enumerate(node.value):
                    walk(child, locator + "/" + str(i), box, prop, lang)
        walk(root, prefix)

    def markdown(self, path):
        lines = (self.root / path).read_text().splitlines(keepends=True)
        i = 0
        while i < len(lines):
            match = re.match(r"^\s*(`{3,}|~{3,})(.*)", lines[i])
            if not match:
                i += 1
                continue
            fence, info = match.groups()
            end = i + 1
            while end < len(lines) and not re.match(r"^\s*" + re.escape(fence[0]) + "{" + str(len(fence)) + r",}\s*$", lines[end]):
                end += 1
            lang_match = re.search(r"data-lang\s*=\s*[\"']?([\w/+.-]+)", info)
            lang = lang_match[1] if lang_match else info.strip().split(maxsplit=1)[0].strip("{}.") if info.strip() else ""
            exported = dict(re.findall(r'(box|name|path)="([^"]+)"', info)) if "[field-property" in info else {}
            prop = exported.get("name", "code" if lang in {"kotlin", "kt"} else "snippet")
            if lang in {"yaml", "yml"}:
                try:
                    body = "".join(lines[i+1:end])
                    if re.search(r"\b(?:operations|code|fragment|vertex|compute):", body):
                        self.transcript(path, body, "fence:" + str(i+1), i+1,
                                        "generated-reference" if path.startswith("public/docs/reference/") else "docs-fence")
                except (ValueError, yaml.YAMLError) as error:
                    self.findings.append(dict(path=path, line=i+1, kind="partial-yaml-fence", detail=str(error).splitlines()[0]))
            if prop in LANGUAGES and lang == "text":
                lang = LANGUAGES[prop]
            if language(lang) in {"kotlin", "kotlinjs", "glsl", "slang", "metal", "stacksl", "rectsl", "javascript", "python", "clojure"}:
                if end == len(lines):
                    self.findings.append(dict(path=path, line=i+1, kind="unterminated-fence"))
                role = "docs-fence"
                extra = dict(fence=info.strip())
                if exported.get("path"):
                    role = "document-mirror"
                    extra["owner"] = (Path(path).parent / exported["path"]).as_posix()
                elif path.startswith("public/docs/reference/"):
                    role = "generated-reference"
                    extra["owner"] = "KDoc/source or reference generator; locate before editing"
                elif re.search(r'\bcapture=[\"\']?ui\b', info):
                    role = "capture-driver"
                self.add(path, "fence:" + str(i+1), "".join(lines[i+1:end]), prop, exported.get("box", ""),
                         lang, i+2, role=role, **extra)
            i = end + 1

    def run(self):
        for path in sorted(self.tracked):
            if Path(path).name not in {"metadata.edn", "metadata.json"} or historical(path):
                continue
            try:
                self.document(path)
            except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
                self.findings.append(dict(path=path, kind="document-parse", detail=str(error)))
        for path in sorted(self.tracked):
            p = Path(path)
            published = path.startswith(PUBLISHED)
            if historical(path):
                if p.suffix in {".kt", ".glsl", ".edn", ".yaml", ".md"}:
                    self.excluded.append(dict(path=path, reason="history"))
                continue
            try:
                if p.suffix in {".yaml", ".yml"} and (path.startswith(("dev_docs/", "templates/", "python/fieldcv/examples/")) or published):
                    self.transcript(path)
                elif p.suffix == ".md" and (published or path.startswith("optional/") and "/examples/" in path):
                    self.markdown(path)
                elif p.suffix[1:] in EXTENSIONS and (published or path in self.sidecars or path.startswith("optional/") and "/examples/" in path or path.startswith("dev_docs/") and p.suffix == ".kt" and "src" not in p.parts or str(p.parent) == "src/jvmMain/resources" and p.suffix == ".glsl"):
                    meta = self.sidecars.get(path, {})
                    if not meta:
                        for prop in sorted(LANGUAGES, key=len, reverse=True):
                            suffix = "_" + prop + "_"
                            if p.stem.endswith(suffix):
                                meta = dict(prop=prop, box=p.stem[:-len(suffix)])
                                break
                        if not meta and p.stem in LANGUAGES:
                            meta = dict(prop=p.stem)
                    lang = meta.get("lang", EXTENSIONS[p.suffix[1:]])
                    if meta.get("tombstone"):
                        self.excluded.append(dict(path=path, reason="tombstoned-box"))
                        continue
                    self.add(path, "source", (self.root / path).read_text(),
                             meta.get("prop", "code" if p.suffix == ".kt" else p.suffix[1:]),
                             meta.get("box", ""), lang, name=meta.get("name", p.stem))
            except (ValueError, OSError, yaml.YAMLError) as error:
                self.findings.append(dict(path=path, kind="parse", detail=str(error)))
        by_hash = defaultdict(list)
        for row in self.rows:
            if row["role"] in {"source", "docs-fence"}:
                by_hash[(row["language"], row["sha256"])].append(row["id"])
        for row in self.rows:
            matches = by_hash[(row["language"], row["sha256"])]
            row["identical_sources"] = [m for m in matches if m != row["id"]]
        self.rows.sort(key=lambda row: (row["path"], row["line"], row["locator"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    inventory = Inventory(ROOT, filter(None, tracked))
    inventory.run()
    editable = [r for r in inventory.rows if r["role"] in {"source", "docs-fence"} and r["language"] in {"kotlin", "kotlinjs", "glsl"}]
    summary = dict(occurrences=len(inventory.rows), review_occurrences=len(editable),
                   review_groups=len({r["group"] for r in editable}),
                   roles=dict(Counter(r["role"] for r in inventory.rows)),
                   review_languages=dict(Counter(r["language"] for r in editable)),
                   review_flags=dict(Counter(flag for r in editable for flag in r["flags"])),
                   findings=len(inventory.findings), excluded_files=len(inventory.excluded))
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "inventory.json").write_text(json.dumps(dict(schema=1,
        source_commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(),
        summary=summary, targets=inventory.rows, findings=inventory.findings, exclusions=inventory.excluded), indent=2) + "\n")
    fields = ["id", "path", "line", "box", "property", "language", "role", "group", "lines", "fragment_breaks", "flags", "sha256", "source_sha256"]
    with (args.out / "targets.tsv").open("w") as stream:
        writer = csv.DictWriter(stream, fields, delimiter="\t", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(dict(row, flags=",".join(row["flags"])) for row in inventory.rows)
    groups = defaultdict(list)
    for row in editable:
        groups[row["group"]].append(row)
    report = ["# Box code inventory", "", "Generated by `tools/inventory_box_code.py --out <directory>` from tracked sources.", "",
              "The source commit identifies the base; hashes describe the files read from the current working tree.",
              "Counts describe occurrences. Repeated create/execute code is listed separately; exact matches",
              "in `inventory.json` help one reviewer keep them together. They do not establish ownership.", "",
              "| Measure | Count |", "| --- | ---: |"]
    report += [f"| {key.replace('_', ' ')} | {value} |" for key, value in summary.items() if isinstance(value, int)]
    report += ["", "| Role | Occurrences |", "| --- | ---: |"]
    report += [f"| {key} | {value} |" for key, value in sorted(summary["roles"].items())]
    report += ["", "| Review flag | Review occurrences |", "| --- | ---: |"]
    report += [f"| {key} | {value} |" for key, value in sorted(summary["review_flags"].items())]
    report += ["", "Flags are reading prompts, not automatic failures. Raw-property calls need a specific reason",
               "to remain in teaching code; deliberate introspection and examples of the escape hatch can justify one."]
    report += ["", "Review groups below include Kotlin/JVM, Kotlin/JS and GLSL. Other languages remain in the manifest.",
               "Docs fences may be partial snippets, before/after programs or capture setup; review their page context.",
               "Shader strings embedded in Kotlin are reviewed with their containing Kotlin occurrence.",
               "Generated starting points, reference pages, document mirrors and inline fallbacks are recorded separately.",
               "Built-in shader defaults are included. Internal library shaders and compiled application/test source are outside this teaching-code pass.",
               "Historical files and tombstoned sidecars are listed in the exclusions, not scheduled for editing.", "",
               "## Review groups", "", "| Group | Kotlin | Kotlin/JS | GLSL | Lines |", "| --- | ---: | ---: | ---: | ---: |"]
    for group, rows in sorted(groups.items()):
        counts = Counter(r["language"] for r in rows)
        report.append(f"| `{group}` | {counts['kotlin']} | {counts['kotlinjs']} | {counts['glsl']} | {sum(r['lines'] for r in rows)} |")
    report += ["", "## Findings requiring inventory review", ""]
    report += ["- `" + f["path"] + "`: " + f["kind"] + " " + f.get("target", f.get("detail", "")) for f in inventory.findings] or ["None."]
    (args.out / "report.md").write_text("\n".join(report) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
