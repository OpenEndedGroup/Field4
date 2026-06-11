#!/usr/bin/env python3
"""Convert a Jupyter notebook (.ipynb) to a Field4 .edn document and copy it to the pasteboard.

Usage:
    python3 tools/ipynb_to_edn.py notebook.ipynb
    python3 tools/ipynb_to_edn.py notebook.ipynb --language python
    python3 tools/ipynb_to_edn.py notebook.ipynb -o output.edn        # write to file instead of pbcopy
    python3 tools/ipynb_to_edn.py notebook.ipynb --no-copy             # just print to stdout

Each code cell becomes a Field4 box.  Markdown cells become boxes whose code
property contains the markdown source (prefixed with a comment so it's visible
but harmless).  Cells are chained: cell N is a child of cell N-1.  The first
cell has no parent — you wire it to the appropriate language root yourself.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import uuid


# ---------------------------------------------------------------------------
# EDN helpers
# ---------------------------------------------------------------------------

def edn_string(s: str) -> str:
    """Escape a Python string for EDN (same rules as Clojure/Java strings)."""
    out = []
    for ch in s:
        if ch == '"':
            out.append('\\"')
        elif ch == '\\':
            out.append('\\\\')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\r':
            out.append('\\r')
        elif ch == '\t':
            out.append('\\t')
        else:
            out.append(ch)
    return '"' + ''.join(out) + '"'


def edn_rect(x: float, y: float, w: float, h: float) -> str:
    return f'#afield/rect [{x} {y} {w} {h}]'


def edn_box_ref(uid: str) -> str:
    return f'#afield/box {edn_string(uid)}'


def edn_set(items: list[str]) -> str:
    if not items:
        return '#{}'
    inner = ' '.join(items)
    return '#{' + inner + '}'


def new_uid() -> str:
    return str(uuid.uuid4())


# ---------------------------------------------------------------------------
# Notebook → EDN
# ---------------------------------------------------------------------------

BOX_WIDTH = 320.0
BOX_HEIGHT = 80.0
BOX_GAP_Y = 30.0
START_X = 100.0
START_Y = 100.0

LANGUAGE_COMMENT = {
    'kotlin': '//',
    'python': '#',
    'clojure': ';;',
    'javascript': '//',
}


def cell_to_code(cell: dict, language: str) -> str:
    """Extract the code string from a notebook cell."""
    source = ''.join(cell.get('source', []))
    cell_type = cell.get('cell_type', 'code')

    if cell_type == 'code':
        return source
    else:
        # For markdown / raw cells, wrap in a comment block so the text is
        # visible but won't cause a compile error.
        comment = LANGUAGE_COMMENT.get(language, '//')
        lines = source.splitlines()
        commented = '\n'.join(f'{comment} {line}' for line in lines)
        return commented


def cell_display_name(cell: dict, index: int) -> str:
    """Derive a short name for a box from its cell."""
    cell_type = cell.get('cell_type', 'code')
    source = ''.join(cell.get('source', [])).strip()

    if cell_type == 'markdown':
        # Use first heading or first line (truncated)
        for line in source.splitlines():
            stripped = line.lstrip('#').strip()
            if stripped:
                return stripped[:60]
    elif source:
        first = source.splitlines()[0].strip()
        if first:
            return first[:60]

    return f'Cell {index + 1}'


def notebook_to_edn(nb: dict, *, language: str | None = None) -> str:
    """Convert a parsed .ipynb dict to a Field4 metadata.edn string."""

    # Detect language from notebook metadata if not provided
    if language is None:
        kernel = nb.get('metadata', {}).get('kernelspec', {})
        lang_info = nb.get('metadata', {}).get('language_info', {})
        detected = (
            kernel.get('language', '')
            or lang_info.get('name', '')
            or 'python'
        ).lower()
        # Normalise common kernel names
        if 'python' in detected:
            language = 'python'
        elif 'kotlin' in detected:
            language = 'kotlin'
        elif 'clojure' in detected:
            language = 'clojure'
        elif 'javascript' in detected or 'js' in detected:
            language = 'javascript'
        else:
            language = detected

    cells = nb.get('cells', [])
    if not cells:
        return '{:version 2\n :boxes []}\n'

    comment = LANGUAGE_COMMENT.get(language, '//')

    # Merge markdown cells into adjacent code cells.
    # Markdown is prepended (as comments) to the next code cell, or appended
    # to the previous code cell if there is no following code cell.
    merged: list[dict] = []   # each entry: {code: str, name: str}
    pending_md: list[str] = []  # accumulated markdown comment blocks

    def md_to_comment(source: str) -> str:
        lines = source.splitlines()
        return '\n'.join(f'{comment} {line}' for line in lines)

    for cell in cells:
        cell_type = cell.get('cell_type', 'code')
        source = ''.join(cell.get('source', []))

        if cell_type != 'code':
            # Accumulate markdown / raw cells
            if source.strip():
                pending_md.append(md_to_comment(source))
            continue

        # Code cell — prepend any accumulated markdown
        code = source
        if pending_md:
            prefix = '\n\n'.join(pending_md) + '\n\n'
            code = prefix + code
            pending_md.clear()

        # Derive name from the first meaningful line of the original source
        name = ''
        for line in source.splitlines():
            stripped = line.strip()
            if stripped:
                name = stripped[:60]
                break
        if not name:
            name = f'Cell {len(merged) + 1}'

        merged.append({'code': code, 'name': name})

    # If there are trailing markdown cells with no code cell after them,
    # append to the last code box (or create one if the notebook is all markdown).
    if pending_md:
        suffix = '\n\n' + '\n\n'.join(pending_md)
        if merged:
            merged[-1]['code'] += suffix
        else:
            # Entire notebook is markdown — create a single box
            merged.append({
                'code': '\n\n'.join(pending_md),
                'name': 'Notebook',
            })

    # Assign UIDs
    box_data: list[dict] = []
    for i, m in enumerate(merged):
        box_data.append({
            'uid': new_uid(),
            'code': m['code'],
            'name': m['name'],
        })

    # Build EDN
    lines = ['{:version 2', ' :boxes [']

    for i, bd in enumerate(box_data):
        uid = bd['uid']
        y = START_Y + i * (BOX_HEIGHT + BOX_GAP_Y)

        props = [
            f':name {edn_string(bd["name"])}',
            f':frame {edn_rect(START_X, y, BOX_WIDTH, BOX_HEIGHT)}',
            f':code {edn_string(bd["code"])}',
        ]

        # Set code_language for non-Kotlin notebooks so Field picks the
        # right execution backend.
        if language and language != 'kotlin':
            lang_value = language.capitalize() if language == 'python' else language
            props.append(f':code_language {edn_string(lang_value)}')

        props_str = '\n                       '.join(props)
        lines.append(f'         {{:id {edn_string(uid)}')
        lines.append(f'          :properties {{{props_str}}}}}')
        if i < len(box_data) - 1:
            lines.append('')

    lines.append(']}')
    return '\n'.join(lines) + '\n'


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description='Convert a Jupyter notebook (.ipynb) to a Field4 .edn document.'
    )
    parser.add_argument('notebook', help='Path to the .ipynb file')
    parser.add_argument(
        '--language', '-l',
        help='Override the code language (kotlin, python, clojure, javascript). '
             'Auto-detected from notebook metadata by default.',
    )
    parser.add_argument(
        '--output', '-o',
        help='Write to a file instead of the pasteboard.',
    )
    parser.add_argument(
        '--no-copy',
        action='store_true',
        help='Print to stdout without copying to pasteboard.',
    )
    args = parser.parse_args()

    with open(args.notebook, 'r', encoding='utf-8') as f:
        nb = json.load(f)

    edn = notebook_to_edn(nb, language=args.language)

    if args.output:
        with open(args.output, 'w', encoding='utf-8') as f:
            f.write(edn)
        print(f'Wrote {args.output}', file=sys.stderr)
    elif args.no_copy:
        sys.stdout.write(edn)
    else:
        # Copy to pasteboard via pbcopy (macOS)
        try:
            proc = subprocess.run(
                ['pbcopy'],
                input=edn.encode('utf-8'),
                check=True,
            )
            cell_count = len(nb.get('cells', []))
            print(
                f'Copied {cell_count}-cell notebook to pasteboard as Field4 .edn',
                file=sys.stderr,
            )
        except FileNotFoundError:
            print(
                'pbcopy not found — printing to stdout instead. '
                'Use -o <file> on non-macOS systems.',
                file=sys.stderr,
            )
            sys.stdout.write(edn)


if __name__ == '__main__':
    main()
