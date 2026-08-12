#!/usr/bin/env python3
"""Extract Field's Faust completion vocabulary from the vendored compiler.

Faust's standard library is ~50 `.lib` files carrying a rigid documentation convention:

    //-----------------------`(os.)oscsin`------------------------
    // Sine wave oscillator.
    //
    // #### Usage
    //
    // ```
    // oscsin(freq) : _
    // ```
    //
    // Where:
    //
    // * `freq`: the frequency in Hz
    //------------------------------------------------------------

That is a machine-readable signature, summary and parameter list for every documented function in
the language, and there are well over a thousand of them. Typing that out by hand would be wrong
somewhere on the first day and wronger at every Faust release; this script reads it instead, so
`faust.js` completes against exactly the library the vendored compiler will actually resolve
`import("stdfaust.lib")` against.

The libraries are not on disk. They ship inside `libfaust-wasm.data`, an Emscripten file-packager
blob whose table of contents is embedded in `libfaust-wasm.js` as the argument to `loadPackage(...)`.
So this reads the table out of the glue, slices the blob, and never needs a `faust` install — the
same reason the binding itself works on a fresh clone.

Usage:
    python tools/generate_faust_vocabulary.py [--check]

    --check   parse and report, but do not write. Exits non-zero if the vocabulary would change,
              which is what a CI guard wants.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FAUST_RES = REPO / "src" / "jvmMain" / "resources" / "faust"
OUT = REPO / "src" / "commonMain" / "resources" / "faust-vocabulary.json"

GLUE = FAUST_RES / "libfaust-wasm.js"
DATA = FAUST_RES / "libfaust-wasm.data"

# The language itself, which no .lib file documents because it is not a library. Grouped the way a
# person learns them rather than alphabetically.
PRIMITIVES: dict[str, list[tuple[str, str]]] = {
    "signal": [
        ("_", "identity — pass one signal through"),
        ("!", "cut — discard one signal"),
        ("mem", "one-sample delay"),
        ("prefix", "delay by one sample with an initial value"),
        ("int", "cast a signal to integer"),
        ("float", "cast a signal to float"),
        ("attach", "force the second signal to be computed, output the first"),
        ("select2", "select2(cond, a, b) — choose between two signals"),
        ("select3", "select3(cond, a, b, c) — choose between three signals"),
        ("rdtable", "rdtable(size, init, index) — read-only table lookup"),
        ("rwtable", "rwtable(size, init, w, wd, r) — read/write table"),
        ("waveform", "waveform{...} — a literal signal table"),
        ("soundfile", 'soundfile("label[url:file]", channels) — load audio'),
    ],
    "ui": [
        ("hslider", 'hslider("label", init, min, max, step) — horizontal slider'),
        ("vslider", 'vslider("label", init, min, max, step) — vertical slider'),
        ("nentry", 'nentry("label", init, min, max, step) — numeric entry'),
        ("button", 'button("label") — momentary, 1 while held'),
        ("checkbox", 'checkbox("label") — latching, 0 or 1'),
        ("hbargraph", 'hbargraph("label", min, max) — horizontal meter'),
        ("vbargraph", 'vbargraph("label", min, max) — vertical meter'),
        ("hgroup", 'hgroup("label", expr) — lay controls out horizontally'),
        ("vgroup", 'vgroup("label", expr) — lay controls out vertically'),
        ("tgroup", 'tgroup("label", expr) — one tab per control'),
    ],
    "structure": [
        ("process", "the entry point — every Faust program defines this"),
        ("import", 'import("stdfaust.lib"); — bring a library into scope'),
        ("library", 'library("filters.lib") — a library as an environment'),
        ("environment", "environment { ... } — a scope as a value"),
        ("component", 'component("file.dsp") — another program as an expression'),
        ("declare", 'declare name "value"; — metadata'),
        ("with", "expr with { definitions } — local definitions"),
        ("letrec", "letrec { 'x = ...; } — mutually recursive signals"),
        ("where", "expr where { definitions } — local definitions"),
        ("case", "case { (pattern) => expr; } — pattern matching"),
        ("seq", "seq(i, n, expr) — sequential composition"),
        ("par", "par(i, n, expr) — parallel composition"),
        ("sum", "sum(i, n, expr) — summed composition"),
        ("prod", "prod(i, n, expr) — multiplied composition"),
        ("route", "route(in, out, pairs) — arbitrary signal routing"),
        ("ffunction", "ffunction(signature, header, library) — call C"),
        ("fconstant", "fconstant(type name, header) — a C constant"),
        ("fvariable", "fvariable(type name, header) — a C variable"),
    ],
    "math": [
        ("sin", "sine"), ("cos", "cosine"), ("tan", "tangent"),
        ("asin", "arc sine"), ("acos", "arc cosine"), ("atan", "arc tangent"),
        ("atan2", "atan2(y, x) — arc tangent of two signals"),
        ("exp", "e to the power"), ("log", "natural logarithm"), ("log10", "base-10 logarithm"),
        ("pow", "pow(x, y) — x to the power y"), ("sqrt", "square root"),
        ("abs", "absolute value"), ("min", "min(x, y)"), ("max", "max(x, y)"),
        ("fmod", "fmod(x, y) — floating-point remainder"),
        ("remainder", "remainder(x, y) — IEEE remainder"),
        ("floor", "round towards minus infinity"), ("ceil", "round towards plus infinity"),
        ("rint", "round to nearest integer"), ("trunc", "round towards zero"),
    ],
}

# The composition operators, which are the part of Faust that looks least like other languages.
OPERATORS: list[tuple[str, str]] = [
    (":", "sequential composition — a : b feeds a's outputs into b"),
    (",", "parallel composition — a , b runs both"),
    ("<:", "split — fan one set of outputs out to more inputs"),
    (":>", "merge — sum several outputs down onto fewer inputs"),
    ("~", "recursion — a ~ b feeds b's output back into a, one sample delayed"),
    ("'", "one-sample delay, postfix — x' is x delayed"),
]


def read_package_table(glue_text: str) -> list[dict]:
    """The Emscripten file-packager table of contents, from the argument to loadPackage()."""
    marker = glue_text.find("loadPackage(")
    if marker < 0:
        raise SystemExit("could not find loadPackage() in libfaust-wasm.js")
    start = glue_text.find("{", marker)
    depth = 0
    for i in range(start, len(glue_text)):
        if glue_text[i] == "{":
            depth += 1
        elif glue_text[i] == "}":
            depth -= 1
            if depth == 0:
                return json.loads(glue_text[start:i + 1])["files"]
    raise SystemExit("loadPackage() argument was not a complete JSON object")


def extract_libs() -> dict[str, str]:
    """Every `.lib` in the preload package, by base name."""
    files = read_package_table(GLUE.read_text(errors="replace"))
    blob = DATA.read_bytes()
    libs: dict[str, str] = {}
    for entry in files:
        name = os.path.basename(entry["filename"])
        if name.endswith(".lib"):
            libs[name] = blob[entry["start"]:entry["end"]].decode("utf-8", errors="replace")
    if not libs:
        raise SystemExit("no .lib files found in libfaust-wasm.data")
    return libs


PREFIX_RE = re.compile(r'^\s*(\w+)\s*=\s*library\("([\w.]+)"\)\s*;', re.M)

# //-----------------------`(os.)oscsin`------------------------
DOC_HEADER_RE = re.compile(r"^//-+`\((\w+)\.\)([\w.]+)`-+\s*$", re.M)
# Some entries document a name with no environment prefix, e.g. `//---`(ba.)take`---` is the norm but
# a few libraries use //---`name`---.
DOC_HEADER_BARE_RE = re.compile(r"^//-+`([\w.]+)`-+\s*$", re.M)
DOC_END_RE = re.compile(r"^//-{10,}\s*$", re.M)


def parse_doc_blocks(lib_name: str, text: str) -> list[dict]:
    """Pull every documented function out of one library file."""
    out: list[dict] = []
    for match in DOC_HEADER_RE.finditer(text):
        prefix, name = match.group(1), match.group(2)
        body_start = match.end()
        end = DOC_END_RE.search(text, body_start)
        body = text[body_start:end.start()] if end else text[body_start:body_start + 4000]
        out.append(_parse_body(prefix, name, lib_name, body))
    return out


BULLET_RE = re.compile(r"^\s*\*\s*`([^`]+)`\s*:\s*(.*)$")


def _parse_body(prefix: str, name: str, lib_name: str, body: str) -> dict:
    """Split a doc body into summary, usage signature and parameter list."""
    lines = [re.sub(r"^//\s?", "", line).rstrip() for line in body.splitlines()]

    summary_lines: list[str] = []
    usage_lines: list[str] = []
    params: list[dict] = []

    section = "summary"
    in_code = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("####"):
            heading = stripped.lstrip("#").strip().lower()
            # Everything after Test/Example is illustrative, not signature.
            section = heading
            in_code = False
            continue
        if stripped.startswith("```"):
            in_code = not in_code
            continue
        if section == "summary":
            if stripped:
                summary_lines.append(stripped)
        elif section == "usage":
            if in_code and stripped:
                usage_lines.append(stripped)
            else:
                bullet = BULLET_RE.match(line)
                if bullet:
                    params.append({"name": bullet.group(1), "doc": bullet.group(2).strip()})

    summary = " ".join(summary_lines).strip()
    # "`oscsin` is a standard Faust function." adds nothing a reader of a completion list needs.
    summary = re.sub(r"\s*`?\w+`? is a standard Faust function\.?", "", summary).strip()

    return {
        "name": f"{prefix}.{name}",
        "prefix": prefix,
        "short": name,
        "lib": lib_name,
        "summary": summary,
        "usage": usage_lines[0] if usage_lines else "",
        "params": params,
    }


def build() -> dict:
    libs = extract_libs()

    std = libs.get("stdfaust.lib", "")
    prefixes = {m.group(1): m.group(2) for m in PREFIX_RE.finditer(std)}
    if not prefixes:
        raise SystemExit("could not parse the environment prefixes out of stdfaust.lib")

    by_lib = {lib: name for name, lib in prefixes.items()}

    functions: list[dict] = []
    seen: set[str] = set()
    for lib_name, text in sorted(libs.items()):
        for entry in parse_doc_blocks(lib_name, text):
            # Trust the prefix the documentation states, but only keep it if stdfaust.lib really
            # binds it — otherwise it is not reachable from an `import("stdfaust.lib")` program.
            if entry["prefix"] not in prefixes:
                continue
            if entry["name"] in seen:
                continue
            seen.add(entry["name"])
            functions.append(entry)

    functions.sort(key=lambda f: f["name"])

    primitives = [
        {"name": name, "summary": doc, "group": group}
        for group, entries in PRIMITIVES.items()
        for name, doc in entries
    ]

    return {
        "_comment": "Generated by tools/generate_faust_vocabulary.py — do not edit by hand.",
        "libfaustVersion": _vendored_version(),
        "prefixes": prefixes,
        "libraryPrefix": by_lib,
        "functions": functions,
        "primitives": primitives,
        "operators": [{"symbol": s, "summary": d} for s, d in OPERATORS],
    }


def _vendored_version() -> str:
    """Keep the vocabulary honest about which compiler it was taken from."""
    provenance = FAUST_RES / "PROVENANCE.md"
    if provenance.is_file():
        match = re.search(r"libfaust ([\d.]+)", provenance.read_text())
        if match:
            return match.group(1)
    return "unknown"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report without writing")
    args = parser.parse_args()

    vocabulary = build()
    rendered = json.dumps(vocabulary, indent=1, ensure_ascii=False) + "\n"

    by_prefix: dict[str, int] = {}
    for fn in vocabulary["functions"]:
        by_prefix[fn["prefix"]] = by_prefix.get(fn["prefix"], 0) + 1
    documented = sum(1 for f in vocabulary["functions"] if f["summary"])
    with_usage = sum(1 for f in vocabulary["functions"] if f["usage"])

    print(f"libfaust {vocabulary['libfaustVersion']}")
    print(f"{len(vocabulary['prefixes'])} library prefixes")
    print(f"{len(vocabulary['functions'])} functions "
          f"({documented} with a summary, {with_usage} with a signature)")
    print(f"{len(vocabulary['primitives'])} primitives, {len(vocabulary['operators'])} operators")
    top = sorted(by_prefix.items(), key=lambda kv: -kv[1])[:8]
    print("largest: " + ", ".join(f"{p}.={n}" for p, n in top))

    if args.check:
        current = OUT.read_text() if OUT.is_file() else ""
        if current != rendered:
            print(f"\n{OUT.relative_to(REPO)} is out of date; re-run without --check")
            return 1
        print(f"\n{OUT.relative_to(REPO)} is up to date")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(rendered)
    print(f"\nwrote {OUT.relative_to(REPO)} ({len(rendered) / 1024:.0f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
