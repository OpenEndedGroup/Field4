#!/usr/bin/env python3
"""Flag ES-module JS that calls a Kotlin/JS export as a bare global instead of importing it.

Kotlin/JS `@JsExport` functions in `src/jsMain/kotlin/afield/*` are exports of the generated
`afield.mjs` module. Inside a `type="module"` script they are NOT globals: a module that calls
`send(...)` without `import { send } from "./afield.mjs"` works only while some legacy script
happens to have copied it onto `window`, and fails with a `ReferenceError` the day that stops.
AGENTS.md used to carry this as a paragraph of advice; this is the check that retires it.

What counts as a module (all under `src/commonMain/resources`, vendored trees excluded):

- a file loaded by any `.html` there through `<script type="module" src=...>`;
- a file with a top-level `import`/`export` statement;
- a file statically imported (`import ... from "./x.js"`) by a module;
- an inline `<script type="module">` block in an `.html` file.

What counts as a Kotlin/JS export: every `@JsExport`-annotated top-level `fun` in
`src/jsMain/kotlin`, read from source each run, so the list cannot drift.

A bare call is `name(` not preceded by `.`, `function `, or an identifier character, in a module
that neither imports `name` nor declares it (`function`/`const`/`let`/`var`/`class`, or as a
parameter-free local). Comments and string literals are blanked before matching.

Deliberate exceptions go in ALLOWED below: one exact `(path, name)` pair per entry, each with a
reason. No wildcards -- an allowance for a file is not an allowance for a name, or the reverse.

Exit status: 0 clean, 1 violations (listed), 2 on a usage or I/O problem.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RESOURCES = Path("src/commonMain/resources")
KOTLIN_JS = Path("src/jsMain/kotlin")
EXCLUDED_DIRS = ("vendor", "min", "monaco")

#: (resource-relative path, identifier) -> why this bare use is deliberate.
ALLOWED: dict[tuple[str, str], str] = {}

JS_EXPORT_FUN = re.compile(
    r"@JsExport\b(?:\s*@\w+(?:\([^)]*\))?)*\s*(?:(?:public|internal|inline|suspend)\s+)*fun\s+"
    r"(?:<[^>]*>\s*)?([A-Za-z_]\w*)\s*\("
)
SCRIPT_TAG = re.compile(r"<script\b([^>]*)>(.*?)</script\s*>", re.S | re.I)
ATTR_SRC = re.compile(r"""\bsrc\s*=\s*["']([^"']+)["']""", re.I)
ATTR_MODULE = re.compile(r"""\btype\s*=\s*["']module["']""", re.I)
TOP_LEVEL_MODULE_SYNTAX = re.compile(r"(?m)^\s*(?:import\s*[\w{*'\"]|export\s)")
STATIC_IMPORT_FROM = re.compile(r"""(?m)^\s*import\s[^;]*?\bfrom\s*["']([^"']+)["']""")
BARE_IMPORT = re.compile(r"""(?m)^\s*import\s*["']([^"']+)["']""")
IMPORT_CLAUSE = re.compile(r"""(?ms)^\s*import\s+(.*?)\s+from\s*["'][^"']+["']""")


def kotlin_js_exports(repo: Path) -> set[str]:
    names: set[str] = set()
    for path in sorted((repo / KOTLIN_JS).rglob("*.kt")):
        names.update(JS_EXPORT_FUN.findall(path.read_text(encoding="utf-8")))
    return names


def blank_comments_and_strings(source: str) -> str:
    """Replace comments and string/template literal contents with spaces, keeping offsets."""
    out = list(source)
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = source.find("\n", i)
            j = n if j < 0 else j
            out[i:j] = " " * (j - i)
            i = j
        elif c == "/" and nxt == "*":
            j = source.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out[i:j] = [ch if ch == "\n" else " " for ch in source[i:j]]
            i = j
        elif c in "'\"`":
            j = i + 1
            while j < n and source[j] != c:
                if source[j] == "\\":
                    j += 1
                elif c != "`" and source[j] == "\n":
                    break
                j += 1
            for k in range(i + 1, min(j, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


def imported_names(source: str) -> set[str]:
    names: set[str] = set()
    for clause in IMPORT_CLAUSE.findall(source):
        clause = clause.replace("{", ",").replace("}", ",")
        for part in clause.split(","):
            part = part.strip()
            if not part or part.startswith("*"):
                part = part.split(" as ", 1)[-1].strip() if " as " in part else ""
            elif " as " in part:
                part = part.split(" as ", 1)[1].strip()
            if re.fullmatch(r"[A-Za-z_$][\w$]*", part):
                names.add(part)
    return names


def declared_names(code: str) -> set[str]:
    declared = set(re.findall(r"\bfunction\s*\*?\s*([A-Za-z_$][\w$]*)", code))
    declared.update(re.findall(r"\bclass\s+([A-Za-z_$][\w$]*)", code))
    declared.update(re.findall(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)", code))
    # Destructured declarations: const { a, b: c } = ...
    for group in re.findall(r"\b(?:const|let|var)\s*\{([^}]*)\}", code):
        for part in group.split(","):
            part = part.split(":")[-1].split("=")[0].strip()
            if part:
                declared.add(part)
    return declared


def bare_calls(code: str, names: set[str]) -> list[tuple[int, str]]:
    found: list[tuple[int, str]] = []
    for match in re.finditer(r"(?<![\w$.])([A-Za-z_$][\w$]*)\s*\(", code):
        name = match.group(1)
        if name not in names:
            continue
        before = code[max(0, match.start() - 12):match.start()]
        if re.search(r"\bfunction\s*$", before):
            continue
        line = code.count("\n", 0, match.start()) + 1
        found.append((line, name))
    return found


def is_excluded(relative: Path) -> bool:
    return any(part in EXCLUDED_DIRS for part in relative.parts[:-1])


def module_units(repo: Path) -> list[tuple[str, str]]:
    """(label, source) for every module file and inline module block under resources."""
    root = repo / RESOURCES
    scripts = {
        path.relative_to(root): path
        for path in root.rglob("*")
        if path.suffix in {".js", ".mjs"} and path.is_file() and not is_excluded(path.relative_to(root))
    }
    modules: set[Path] = set()
    inline: list[tuple[str, str]] = []
    for html in sorted(root.rglob("*.html")):
        rel_html = html.relative_to(root)
        if is_excluded(rel_html):
            continue
        text = html.read_text(encoding="utf-8", errors="replace")
        for index, match in enumerate(SCRIPT_TAG.finditer(text)):
            attrs, body = match.group(1), match.group(2)
            if not ATTR_MODULE.search(attrs):
                continue
            src = ATTR_SRC.search(attrs)
            if src is None:
                line = text.count("\n", 0, match.start(2)) + 1
                inline.append((f"{rel_html}:inline-module@{line}", "\n" * (line - 1) + body))
                continue
            target = src.group(1).split("?", 1)[0].split("#", 1)[0]
            candidate = (rel_html.parent / target.lstrip("/")) if not target.startswith("/") else Path(target.lstrip("/"))
            candidate = Path(*[p for p in candidate.parts if p != "."])
            if candidate in scripts:
                modules.add(candidate)
    for rel, path in scripts.items():
        if TOP_LEVEL_MODULE_SYNTAX.search(blank_comments_and_strings(path.read_text(encoding="utf-8", errors="replace"))):
            modules.add(rel)
    # Transitive static imports of modules are modules.
    pending = list(modules)
    while pending:
        rel = pending.pop()
        source = scripts[rel].read_text(encoding="utf-8", errors="replace")
        for target in STATIC_IMPORT_FROM.findall(source) + BARE_IMPORT.findall(source):
            if not target.startswith("."):
                continue
            candidate = Path(*[p for p in (rel.parent / target).parts if p != "."])
            if candidate in scripts and candidate not in modules:
                modules.add(candidate)
                pending.append(candidate)
    units = [(str(rel), scripts[rel].read_text(encoding="utf-8", errors="replace")) for rel in sorted(modules)]
    # afield.mjs is the Kotlin/JS output itself, not a consumer of it.
    units = [(label, source) for label, source in units if Path(label).name != "afield.mjs"]
    return units + inline


def violations(repo: Path) -> tuple[list[str], set[tuple[str, str]]]:
    exports = kotlin_js_exports(repo)
    problems: list[str] = []
    used_allowances: set[tuple[str, str]] = set()
    for label, source in module_units(repo):
        code = blank_comments_and_strings(source)
        available = imported_names(source) | declared_names(code)
        for line, name in bare_calls(code, exports - available):
            key = (label.split(":inline-module@", 1)[0] if ":inline-module@" in label else label, name)
            if key in ALLOWED:
                used_allowances.add(key)
                continue
            problems.append(
                f"{RESOURCES / label.split(':inline-module@')[0]}:{line}: bare `{name}(` in a module; "
                f'add `import {{ {name} }} from "./afield.mjs";` (it is a Kotlin/JS export, not a global)'
            )
    return problems, used_allowances


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--repo", type=Path, default=REPO_ROOT)
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    if not (repo / RESOURCES).is_dir() or not (repo / KOTLIN_JS).is_dir():
        print(f"check_module_imports: {repo} has no {RESOURCES} or {KOTLIN_JS}", file=sys.stderr)
        return 2
    problems, used = violations(repo)
    stale = sorted(set(ALLOWED) - used)
    for key in stale:
        problems.append(f"ALLOWED entry {key} no longer matches anything; delete it")
    if problems:
        print("\n".join(problems))
        print(f"check_module_imports: {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print(f"check_module_imports: {len(module_units(repo))} module unit(s) clean")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
