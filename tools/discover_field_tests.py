#!/usr/bin/env python3
"""Discover FIELD_TEST annotations and emit a catalog JSON document."""

from __future__ import annotations

import argparse
import ast
import json
import re
import sys
import textwrap
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_ROOTS = [
    "src/jvmMain/kotlin/afield",
    "dev_docs",
    "public/docs",
    "metadata_store",
]

COMMENT_PATTERNS = [
    re.compile(r"/\*(.*?)\*/", re.DOTALL),
    re.compile(r"<!--(.*?)-->", re.DOTALL),
]

KOTLIN_SNIPPET_RE = re.compile(
    r"""(?sx)
    \A
    (?P<leading>
        (?:
            \s+
          | //[^\n]*\n
          | /\*.*?\*/\s*
          | @[^\n]+\n
        )*
    )
    (?P<declaration>
        (?:
            (?:private|internal|public|protected)\s+
        )*
        (?:const\s+)?
        (?:val|var)\s+
        (?P<name>[A-Za-z_][A-Za-z0-9_]*)
        \s*=\s*
    )
    \"\"\"
    (?P<body>.*?)
    \"\"\"
    (?P<trim>
        \s*
        \.
        \s*
        trim(?:Indent|Margin)
        \s*
        \([^)]*\)
    )?
    """,
)

DIFF_SECTION_NAMES = {"before", "after", "static"}
DIFF_FENCE_KINDS = {"diff", "diff_runner"}
DIFFSTATIC_FENCE_KINDS = {"diffstatic", "diffstatic_runner"}
FABRICATE_DOCS_SCENES = {
    "default-scene": ("renderDefaultScene", "fabricate default scene ready"),
    "shiny-torii-checkerboard": ("renderShinyToriCheckerboard", "fabricate shiny checkerboard scene ready"),
    "camera-depth-study": ("renderCheckerboardCameraDepthStudy", "fabricate camera depth study ready"),
    "shader-generated-checkerboard": ("renderShaderGeneratedCheckerboard", "fabricate shader checkerboard scene ready"),
    "metalness": ("renderMetalnessComparison", "fabricate metalness comparison ready"),
    "shadows": ("renderShadowComparison", "fabricate shadow comparison ready"),
    "ambient-occlusion": ("renderAoComparison", "fabricate ao comparison ready"),
    "blended-transparency": ("renderBlendedTransparency", "fabricate blended transparency ready"),
    "post-stack-vignette": ("renderPostStackVignette", "fabricate post stack vignette ready"),
}


@dataclass
class Token:
    indent: int
    text: str
    line_no: int


@dataclass
class AnnotationBlock:
    line_no: int
    start_offset: int
    end_offset: int
    annotation: dict[str, Any]


class ParseError(ValueError):
    pass


class AnnotationParser:
    def __init__(self, tokens: list[Token]) -> None:
        self.tokens = tokens
        self.index = 0

    def parse(self) -> dict[str, Any]:
        if not self.tokens:
            raise ParseError("annotation has no metadata")
        value = self._parse_block(self.tokens[0].indent)
        if not isinstance(value, dict):
            raise ParseError("top-level metadata must be a mapping")
        if self.index != len(self.tokens):
            token = self.tokens[self.index]
            raise ParseError(f"unexpected trailing content on line {token.line_no}")
        return value

    def _parse_block(self, indent: int) -> Any:
        if self.index >= len(self.tokens):
            raise ParseError("unexpected end of annotation")
        token = self.tokens[self.index]
        if token.indent < indent:
            raise ParseError(f"unexpected dedent on line {token.line_no}")
        if token.text.startswith("- "):
            return self._parse_list(indent)
        return self._parse_map(indent)

    def _parse_map(self, indent: int) -> dict[str, Any]:
        data: dict[str, Any] = {}
        while self.index < len(self.tokens):
            token = self.tokens[self.index]
            if token.indent < indent:
                break
            if token.indent != indent:
                raise ParseError(f"unexpected indentation on line {token.line_no}")
            if token.text.startswith("- "):
                break
            key, sep, rest = token.text.partition(":")
            if not sep:
                raise ParseError(f"expected key/value pair on line {token.line_no}")
            key = key.strip()
            rest = rest.strip()
            self.index += 1
            if not key:
                raise ParseError(f"empty key on line {token.line_no}")
            if rest:
                data[key] = parse_scalar(rest)
                continue
            if self.index >= len(self.tokens) or self.tokens[self.index].indent <= token.indent:
                raise ParseError(f"missing nested block for '{key}' on line {token.line_no}")
            data[key] = self._parse_block(self.tokens[self.index].indent)
        return data

    def _parse_list(self, indent: int) -> list[Any]:
        items: list[Any] = []
        while self.index < len(self.tokens):
            token = self.tokens[self.index]
            if token.indent < indent:
                break
            if token.indent != indent or not (token.text == "-" or token.text.startswith("- ")):
                raise ParseError(f"unexpected list content on line {token.line_no}")
            rest = token.text[1:].strip()
            self.index += 1
            if rest:
                if ":" in rest:
                    key, sep, value = rest.partition(":")
                    key = key.strip()
                    value = value.strip()
                    if sep and key:
                        item: dict[str, Any] = {}
                        item[key] = parse_scalar(value) if value else ""
                        if self.index < len(self.tokens) and self.tokens[self.index].indent > token.indent:
                            nested = self._parse_block(self.tokens[self.index].indent)
                            if not isinstance(nested, dict):
                                raise ParseError(
                                    f"expected mapping continuation for list item on line {token.line_no}"
                                )
                            item.update(nested)
                        items.append(item)
                        continue
                items.append(parse_scalar(rest))
                continue
            if self.index >= len(self.tokens) or self.tokens[self.index].indent <= token.indent:
                raise ParseError(f"missing nested list item content on line {token.line_no}")
            items.append(self._parse_block(self.tokens[self.index].indent))
        return items


def parse_scalar(value: str) -> Any:
    value = value.strip()
    if not value:
        return ""
    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1].strip()
        if not inner:
            return []
        return [parse_scalar(part) for part in split_inline_list(inner)]
    if value[0] in {"'", '"'}:
        return ast.literal_eval(value)
    if value == "true":
        return True
    if value == "false":
        return False
    if value == "null":
        return None
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    if re.fullmatch(r"-?\d+\.\d+", value):
        return float(value)
    return value


def split_inline_list(text: str) -> list[str]:
    items: list[str] = []
    current: list[str] = []
    quote: str | None = None
    escape = False
    for char in text:
        if quote:
            current.append(char)
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == quote:
                quote = None
            continue
        if char in {"'", '"'}:
            quote = char
            current.append(char)
            continue
        if char == ",":
            items.append("".join(current).strip())
            current = []
            continue
        current.append(char)
    if quote:
        raise ParseError("unterminated quoted string in inline list")
    final = "".join(current).strip()
    if final:
        items.append(final)
    return items


def normalize_comment_body(body: str) -> list[str]:
    lines = body.splitlines()
    cleaned: list[str] = []
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith("*"):
            line = stripped[1:]
            if line.startswith(" "):
                line = line[1:]
        cleaned.append(line.rstrip())
    while cleaned and not cleaned[0].strip():
        cleaned.pop(0)
    while cleaned and not cleaned[-1].strip():
        cleaned.pop()
    return cleaned


def build_tokens(lines: list[str], start_line: int) -> list[Token]:
    tokens: list[Token] = []
    for offset, raw_line in enumerate(lines):
        if not raw_line.strip():
            continue
        indent = len(raw_line) - len(raw_line.lstrip(" "))
        tokens.append(Token(indent=indent, text=raw_line.strip(), line_no=start_line + offset))
    return tokens


def mask_markdown_code_fences(text: str) -> str:
    lines = text.splitlines(keepends=True)
    masked: list[str] = []
    fence_delimiter: str | None = None
    for line in lines:
        stripped = line.lstrip()
        if fence_delimiter is None:
            if stripped.startswith("```") or stripped.startswith("~~~"):
                fence_delimiter = stripped[:3]
                masked.append(line)
                continue
            masked.append(line)
            continue
        if stripped.startswith(fence_delimiter):
            fence_delimiter = None
            masked.append(line)
            continue
        masked.append("".join(char if char in {"\r", "\n"} else " " for char in line))
    return "".join(masked)


def find_annotation_blocks(path: Path, text: str) -> list[AnnotationBlock]:
    scan_text = mask_markdown_code_fences(text) if path.suffix.lower() in {".md", ".markdown"} else text
    blocks: list[AnnotationBlock] = []
    for pattern in COMMENT_PATTERNS:
        for match in pattern.finditer(scan_text):
            body_lines = normalize_comment_body(match.group(1))
            if not body_lines:
                continue
            marker_index = next(
                (index for index, line in enumerate(body_lines) if line.strip() == "FIELD_TEST"),
                None,
            )
            if marker_index is None:
                continue
            metadata_lines = body_lines[marker_index + 1 :]
            line_no = scan_text.count("\n", 0, match.start()) + 1 + marker_index
            tokens = build_tokens(metadata_lines, line_no + 1)
            parser = AnnotationParser(tokens)
            blocks.append(
                AnnotationBlock(
                    line_no=line_no,
                    start_offset=match.start(),
                    end_offset=match.end(),
                    annotation=parser.parse(),
                )
            )
    return blocks


def iter_candidate_files(root: Path) -> list[Path]:
    files: list[Path] = []
    if root.is_file():
        return [root]
    for path in root.rglob("*"):
        if path.is_file():
            files.append(path)
    return files


def validate_annotation(path: Path, line_no: int, annotation: dict[str, Any]) -> dict[str, Any]:
    for key in ("id", "runner"):
        if key not in annotation:
            raise ParseError(f"{path}:{line_no}: missing required field '{key}'")
    runner = annotation["runner"]
    if runner == "jvm-main" and "entrypoint" not in annotation:
        raise ParseError(f"{path}:{line_no}: jvm-main tests require 'entrypoint'")
    if runner not in {"jvm-main", "backdoor-box", "backdoor-document"}:
        raise ParseError(f"{path}:{line_no}: unsupported runner '{runner}'")
    return annotation


def snippet_language_for_path(path: Path) -> str:
    if path.suffix.lower() in {".kt", ".kts"}:
        return "kotlin"
    return path.suffix.lower().lstrip(".") or "text"


def normalize_snippet(body: str, *, trim_indent: bool) -> str:
    normalized = body.replace("\r\n", "\n").replace("\r", "\n")
    if trim_indent:
        normalized = textwrap.dedent(normalized).strip("\n")
    else:
        normalized = normalized.strip("\n")
    return normalized


def line_number_at(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def first_fence_token(info: str) -> str:
    stripped = info.strip()
    if not stripped:
        return ""
    token = stripped.split()[0]
    if token.startswith("{"):
        return ""
    return token


def language_from_fence_info(info: str, *, default: str) -> str:
    match = re.search(r'data-lang\s*=\s*"?([A-Za-z0-9_+\-]+)"?', info)
    if match:
        return match.group(1)
    token = first_fence_token(info)
    if token and token not in DIFF_FENCE_KINDS | DIFFSTATIC_FENCE_KINDS:
        return token
    return default


def fence_attr(info: str, *names: str) -> str | None:
    for name in names:
        pattern = rf"""(?x)
            (?:^|\s)
            {re.escape(name)}
            \s*=\s*
            (?:
                "([^"]*)"
              | '([^']*)'
              | ([^\s}}]+)
            )
        """
        match = re.search(pattern, info)
        if match:
            return next(value for value in match.groups() if value is not None)
    return None


def annotation_image_path(annotation: dict[str, Any]) -> str | None:
    expect = annotation.get("expect")
    if not isinstance(expect, dict):
        return None
    image = expect.get("image")
    if not isinstance(image, dict):
        return None
    path = image.get("path")
    return str(path) if path else None


def fabricate_docs_harness_code(*, scene: str, output_path: str) -> str:
    if scene not in FABRICATE_DOCS_SCENES:
        supported = ", ".join(sorted(FABRICATE_DOCS_SCENES))
        raise ParseError(f"unsupported fabricate-scene '{scene}', expected one of: {supported}")
    method_name, ready_message = FABRICATE_DOCS_SCENES[scene]
    output_literal = json.dumps(output_path)
    ready_literal = json.dumps(ready_message)
    return f"""
import afield.four.Renderer
import afield.four.fabricated.StandardRendererDocsHarness
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()

val captureScene: suspend () -> Unit = {{
    StandardRendererDocsHarness.{method_name}({output_literal}, renderer = renderer)
    println({ready_literal})
}}
start(captureScene)
""".strip()


def split_markdown_sections(body: str, *, body_start_line: int) -> dict[str, dict[str, Any]]:
    sections: dict[str, dict[str, Any]] = {}
    current_name: str | None = None
    current_lines: list[str] = []
    current_line = body_start_line
    for offset, line in enumerate(body.splitlines()):
        stripped = line.strip()
        if stripped.startswith(":::"):
            candidate = stripped[3:].strip()
            if candidate in DIFF_SECTION_NAMES:
                if current_name is not None:
                    sections[current_name] = {
                        "text": "\n".join(current_lines).strip("\n"),
                        "line": current_line,
                    }
                current_name = candidate
                current_lines = []
                current_line = body_start_line + offset + 1
                continue
        current_lines.append(line)
    if current_name is not None:
        sections[current_name] = {
            "text": "\n".join(current_lines).strip("\n"),
            "line": current_line,
        }
    return sections


def choose_markdown_section(
    path: Path,
    block: AnnotationBlock,
    annotation: dict[str, Any],
    *,
    fence_kind: str,
    sections: dict[str, dict[str, Any]],
) -> tuple[str, dict[str, Any]]:
    requested = annotation.get("section")
    if requested is not None:
        requested = str(requested)
        section = sections.get(requested)
        if section is None:
            raise ParseError(
                f"{path}:{block.line_no}: requested section '{requested}' is not present in the following fence"
            )
        return requested, section

    if fence_kind in DIFF_FENCE_KINDS and "after" in sections:
        return "after", sections["after"]
    if fence_kind in DIFFSTATIC_FENCE_KINDS and "static" in sections:
        return "static", sections["static"]
    for preferred in ("after", "static", "before"):
        if preferred in sections:
            return preferred, sections[preferred]
    if len(sections) == 1:
        name = next(iter(sections))
        return name, sections[name]
    raise ParseError(
        f"{path}:{block.line_no}: could not choose a runnable section from fence sections {sorted(sections)}"
    )


def bind_kotlin_snippet(path: Path, text: str, block: AnnotationBlock, annotation: dict[str, Any]) -> dict[str, Any]:
    tail = text[block.end_offset :]
    match = KOTLIN_SNIPPET_RE.match(tail)
    if match is None:
        raise ParseError(
            f"{path}:{block.line_no}: backdoor-box annotation in Kotlin must be followed by a triple-quoted val/var snippet"
        )
    body_offset = block.end_offset + match.start("body")
    trim_indent = bool(match.group("trim"))
    return {
        "snippet": normalize_snippet(match.group("body"), trim_indent=trim_indent),
        "snippetLanguage": str(annotation.get("language") or snippet_language_for_path(path)),
        "snippetName": match.group("name"),
        "snippetSourceLine": line_number_at(text, body_offset),
        "snippetSourceKind": "kotlin-string",
    }


def bind_markdown_fence(path: Path, text: str, block: AnnotationBlock, annotation: dict[str, Any]) -> dict[str, Any]:
    lines = text.splitlines(keepends=True)
    line_index = 0
    offset = 0
    while line_index < len(lines):
        next_offset = offset + len(lines[line_index])
        if next_offset > block.end_offset:
            if block.end_offset > offset:
                offset = next_offset
                line_index += 1
            break
        offset = next_offset
        line_index += 1

    while line_index < len(lines):
        line = lines[line_index]
        next_offset = offset + len(line)
        if not line.strip():
            offset = next_offset
            line_index += 1
            continue
        stripped = line.lstrip()
        if not (stripped.startswith("```") or stripped.startswith("~~~")):
            raise ParseError(f"{path}:{block.line_no}: expected a code fence immediately after FIELD_TEST")
        fence_delimiter = stripped[:3]
        fence_info = stripped[3:].strip()
        body_lines: list[str] = []
        body_start_line = line_index + 2
        for body_index in range(line_index + 1, len(lines)):
            body_line = lines[body_index]
            body_stripped = body_line.lstrip()
            if body_stripped.startswith(fence_delimiter):
                raw_body = "".join(body_lines)
                fabricate_scene = fence_attr(fence_info, "fabricate-scene", "fabricate_scene", "fabricateScene")
                if fabricate_scene:
                    output_path = (
                        fence_attr(fence_info, "source-image", "source_image", "native-image", "native_image")
                        or annotation_image_path(annotation)
                    )
                    if not output_path:
                        raise ParseError(
                            f"{path}:{block.line_no}: fabricate-scene fences require source-image or expect.image.path"
                        )
                    return {
                        "snippet": fabricate_docs_harness_code(scene=fabricate_scene, output_path=output_path),
                        "snippetLanguage": str(annotation.get("language") or language_from_fence_info(fence_info, default="text")),
                        "snippetSourceLine": body_start_line,
                        "snippetSourceKind": "markdown-fence",
                        "snippetFenceInfo": fence_info,
                        "snippetSection": "fabricate-scene",
                    }
                sections = split_markdown_sections(raw_body, body_start_line=body_start_line)
                fence_kind = first_fence_token(fence_info)
                if sections:
                    section_name, section = choose_markdown_section(
                        path,
                        block,
                        annotation,
                        fence_kind=fence_kind,
                        sections=sections,
                    )
                    snippet = section["text"]
                    snippet_line = int(section["line"])
                else:
                    section_name = "body"
                    snippet = raw_body.strip("\n")
                    snippet_line = body_start_line
                return {
                    "snippet": snippet,
                    "snippetLanguage": str(annotation.get("language") or language_from_fence_info(fence_info, default="text")),
                    "snippetSourceLine": snippet_line,
                    "snippetSourceKind": "markdown-fence",
                    "snippetFenceInfo": fence_info,
                    "snippetSection": section_name,
                }
            body_lines.append(body_line)
        raise ParseError(f"{path}:{block.line_no}: unterminated code fence after FIELD_TEST")

    raise ParseError(f"{path}:{block.line_no}: missing code fence after FIELD_TEST")


def bind_annotation(path: Path, text: str, block: AnnotationBlock, annotation: dict[str, Any]) -> dict[str, Any]:
    runner = str(annotation["runner"])
    if runner != "backdoor-box":
        return {}
    if path.suffix.lower() in {".md", ".markdown"}:
        return bind_markdown_fence(path, text, block, annotation)
    return bind_kotlin_snippet(path, text, block, annotation)


def discover(repo_root: Path, roots: list[str]) -> dict[str, Any]:
    tests: list[dict[str, Any]] = []
    errors: list[str] = []
    seen_ids: dict[str, str] = {}
    for root_name in roots:
        root = (repo_root / root_name).resolve() if not Path(root_name).is_absolute() else Path(root_name)
        if not root.exists():
            continue
        for path in iter_candidate_files(root):
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            except OSError as exc:
                errors.append(f"{path}: failed to read file: {exc}")
                continue
            if "FIELD_TEST" not in text:
                continue
            try:
                blocks = find_annotation_blocks(path, text)
                for block in blocks:
                    annotation = validate_annotation(path, block.line_no, block.annotation)
                    annotation_id = str(annotation["id"])
                    previous = seen_ids.get(annotation_id)
                    if previous is not None:
                        raise ParseError(
                            f"{path}:{block.line_no}: duplicate test id '{annotation_id}' already declared at {previous}"
                        )
                    seen_ids[annotation_id] = f"{path}:{block.line_no}"
                    record = dict(annotation)
                    record.update(bind_annotation(path, text, block, annotation))
                    record["sourceFile"] = str(path.relative_to(repo_root))
                    record["sourceLine"] = block.line_no
                    tests.append(record)
            except ParseError as exc:
                errors.append(str(exc))
    if errors:
        raise ParseError("\n".join(errors))
    tests.sort(key=lambda item: str(item["id"]))
    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "repoRoot": str(repo_root),
        "tests": tests,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        action="append",
        dest="roots",
        help="Relative or absolute root to scan. Defaults to the phase-1 discovery roots.",
    )
    parser.add_argument(
        "--output",
        help="Write the catalog JSON to this path instead of stdout.",
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Pretty-print JSON output.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    roots = args.roots or DEFAULT_ROOTS
    try:
        catalog = discover(repo_root, roots)
    except ParseError as exc:
        print(exc, file=sys.stderr)
        return 1
    json_text = json.dumps(catalog, indent=2 if args.pretty else None, sort_keys=False)
    if args.output:
        output_path = Path(args.output)
        if not output_path.is_absolute():
            output_path = repo_root / output_path
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json_text + "\n", encoding="utf-8")
        return 0
    print(json_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
