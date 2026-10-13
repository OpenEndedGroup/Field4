"""Lossless container spans. No document loading, macro replay, or user-code execution."""
from __future__ import annotations

from dataclasses import dataclass, field
import json
from pathlib import Path
import re
from typing import Callable


class ContainerError(ValueError):
    pass


@dataclass
class Unit:
    identity: str
    code: str
    start: int
    end: int
    encode: Callable[[str], str] = field(default=lambda s: s, repr=False)
    script: bool = True
    sidecar: str | None = None


@dataclass
class Extraction:
    units: list[Unit] = field(default_factory=list)
    findings: list[dict] = field(default_factory=list)
    references: list[str] = field(default_factory=list)


def issue(kind: str, detail: str, **extra) -> dict:
    return {"kind": kind, "detail": detail, **extra}


def patch(text: str, edits: list[tuple[int, int, str]]) -> str:
    cursor = len(text)
    for start, end, value in sorted(edits, reverse=True):
        if not 0 <= start <= end <= cursor:
            raise ContainerError("overlapping or invalid container spans")
        text = text[:start] + value + text[end:]
        cursor = start
    return text


def quoted(text: str) -> str:
    return json.dumps(text, ensure_ascii=False)


@dataclass
class Node:
    kind: str
    start: int
    end: int
    value: str = ""
    children: list[Node] = field(default_factory=list)

    def mapping(self) -> dict[str, Node]:
        if self.kind != "map":
            raise ContainerError("expected a map")
        result = {}
        for key, value in zip(self.children[::2], self.children[1::2]):
            if key.kind not in ("string", "atom"):
                continue
            name = key.value.removeprefix(":")
            if name in result:
                raise ContainerError(f"duplicate map key: {name}")
            result[name] = value
        return result


class EdnSpans:
    """Read structure and literal spans without materializing custom tags or reprinting a document.

    Unknown tags are preserved. Reader extensions/discards are rejected rather than misread.
    Only recognized string values are ever patched. Field's escaped strings use JSON-compatible
    escapes; unsupported reader syntax is an explicit finding, never a partial document edit.
    """
    def __init__(self, text: str):
        self.text = text
        self.pos = 0

    def space(self):
        while self.pos < len(self.text):
            if self.text[self.pos].isspace() or self.text[self.pos] == ',':
                self.pos += 1
            elif self.text[self.pos] == ';':
                end = self.text.find('\n', self.pos)
                self.pos = len(self.text) if end < 0 else end + 1
            else:
                break

    def read(self, depth: int = 0) -> Node:
        if depth > 100:
            raise ContainerError("EDN nesting exceeds 100")
        self.space()
        start = self.pos
        if start == len(self.text):
            raise ContainerError("unexpected end of EDN")
        char = self.text[start]
        if char == '"':
            self.pos += 1
            while self.pos < len(self.text):
                c = self.text[self.pos]
                self.pos += 1
                if c == '\\':
                    self.pos += 1
                elif c == '"':
                    try:
                        value = json.loads(self.text[start:self.pos], strict=False)
                    except ValueError as error:
                        raise ContainerError(f"unsupported EDN string at {start}") from error
                    return Node("string", start, self.pos, value)
            raise ContainerError("unterminated EDN string")
        if char in '[{(' or self.text.startswith('#{', start):
            is_set = self.text.startswith('#{', start)
            opener = '{' if is_set else char
            self.pos += 2 if is_set else 1
            closer = {'[': ']', '{': '}', '(': ')'}[opener]
            children = []
            while True:
                self.space()
                if self.pos >= len(self.text):
                    raise ContainerError("unterminated EDN collection")
                if self.text[self.pos] == closer:
                    self.pos += 1
                    break
                children.append(self.read(depth + 1))
            kind = 'set' if is_set else 'map' if opener == '{' else 'seq'
            if kind == 'map' and len(children) % 2:
                raise ContainerError("odd EDN map")
            return Node(kind, start, self.pos, children=children)
        if char == '#':
            match = re.match(r'#[A-Za-z][\w./-]*', self.text[start:])
            if not match:
                raise ContainerError(f"unsupported EDN reader form at {start}")
            self.pos += len(match[0])
            child = self.read(depth + 1)
            return Node('tag', start, self.pos, match[0][1:], [child])
        # Character literals can contain delimiters, including a literal double quote.
        if char == '\\':
            self.pos += 1
            if self.pos == len(self.text):
                raise ContainerError("unfinished EDN character")
            self.pos += 1
            while self.pos < len(self.text) and not self.text[self.pos].isspace() and self.text[self.pos] not in ',[]{}()':
                self.pos += 1
            return Node('character', start, self.pos, self.text[start:self.pos])
        match = re.match(r'[^\s,\[\]{}()";]+', self.text[start:])
        if not match:
            raise ContainerError(f"unexpected EDN delimiter at {start}")
        self.pos += len(match[0])
        return Node('atom', start, self.pos, match[0])

    def document(self) -> Node:
        node = self.read()
        self.space()
        if self.pos != len(self.text):
            raise ContainerError("multiple EDN root values")
        return node


def edn(text: str, properties: set[str]) -> Extraction:
    result = Extraction()
    tree = EdnSpans(text).document()
    if tree.kind == 'map':
        boxes = tree.mapping().get('boxes')
        if boxes is None:
            raise ContainerError("EDN is not a Field document (no boxes)")
    elif tree.kind == 'seq':
        boxes = tree
    else:
        raise ContainerError("EDN is not a Field document")
    if boxes.kind != 'seq':
        raise ContainerError("boxes is not a sequence")
    for index, box in enumerate(boxes.children):
        fields = box.mapping()
        props = fields.get('properties')
        if props is None:
            raise ContainerError("box has no properties")
        props = props.mapping()
        identity = fields.get('id', Node('string', 0, 0, str(index))).value
        language = next((props[k].value.lower() for k in ('code_language', 'language', 'codeLanguage', 'mainLanguage') if k in props), 'kotlin')
        if ('mainLanguage' in props and props['mainLanguage'].value.lower() not in ('kotlin', '', 'nil')) or any(
            key.startswith('is') and key.endswith('Root') and node.value == 'true' for key, node in props.items()
        ):
            result.findings.append(issue('inherited-language-context', f'{identity}: language-root topology needs review'))
        for key, node in props.items():
            typed_kotlin = False
            if node.kind == 'tag' and node.value in ('afield/file', 'afield/file-inline'):
                payload = node.children[0]
                path_node = payload.mapping().get('path') if node.value == 'afield/file-inline' else payload
                typed_kotlin = bool(path_node and path_node.kind == 'string' and Path(path_node.value).suffix in ('.kt', '.kts'))
            declared_language = props.get(key + '_language')
            typed_kotlin = typed_kotlin or bool(declared_language and declared_language.value.lower() in ('kotlin', 'kt'))
            if key not in properties and not typed_kotlin:
                continue
            if key == 'code' and language not in ('kotlin', 'kt', ''):
                result.findings.append(issue('language-context', f'{identity}/{key}: {language}; review inherited language before touching this document'))
                continue
            label = f'{identity}/{key}'
            if node.kind == 'string':
                result.units.append(Unit(label, node.value, node.start, node.end, quoted))
            elif node.kind == 'tag' and node.value == 'afield/file-inline':
                data = node.children[0].mapping()
                path, inline = data.get('path'), data.get('inline')
                if not path or path.kind != 'string' or not inline or inline.kind != 'string':
                    raise ContainerError(f'malformed file-inline: {label}')
                if Path(path.value).suffix not in ('.kt', '.kts'):
                    result.findings.append(issue('other-language', f'{label}: non-Kotlin sidecar', retained=True))
                    continue
                result.units.append(Unit(label, inline.value, inline.start, inline.end, quoted, sidecar=path.value))
                result.references.append(path.value)
            elif node.kind == 'tag' and node.value == 'afield/file':
                path = node.children[0]
                if path.kind != 'string':
                    raise ContainerError(f'malformed file reference: {label}')
                result.references.append(path.value)
            else:
                result.findings.append(issue('unsupported-property', label))
    return result


def _doc_marker(line: str) -> bool:
    # Same wrapper grammar as public/docs_hooks.py:is_marker_line. These are not Kotlin.
    value = line.strip()
    return value in {':::static', ':::before', ':::after', ':::', ':::fold', ':::endfold'} or value.startswith(':::fold ')


def _markdown_code_unit(raw: list[str], indent: str, identity: str, start: int, end: int, wrapped: bool) -> Unit:
    code = ''.join(line[len(indent):] if line.startswith(indent) else line for line in raw)
    sentinel = '//__FIELD_REFACTOR_DOC_MARKER__'
    if wrapped and sentinel in code:
        raise ContainerError('documentation marker sentinel collides with source')
    marker_count = 0
    if wrapped:
        prepared = []
        for line in code.splitlines(keepends=True):
            if _doc_marker(line):
                marker_count += 1
                line = sentinel + line
            prepared.append(line)
        code = ''.join(prepared)

    def encode(value):
        if wrapped:
            restored = []
            seen = 0
            for line in value.splitlines(keepends=True):
                if line.startswith(sentinel):
                    line = line[len(sentinel):]
                    if not _doc_marker(line):
                        raise ContainerError('documentation marker changed shape')
                    seen += 1
                restored.append(line)
            if seen != marker_count:
                raise ContainerError('documentation markers lost during rewrite')
            value = ''.join(restored)
        return ''.join(indent + line if line.strip() else line for line in value.splitlines(keepends=True))

    return Unit(identity, code, start, end, encode)


def markdown(text: str, include_prose=False) -> Extraction:
    result = Extraction()
    lines = text.splitlines(keepends=True)
    offsets = [0]
    for line in lines:
        offsets.append(offsets[-1] + len(line))
    index = 0
    prose_start = 0
    while index < len(lines):
        match = re.match(r'^(\s*)(`{3,}|~{3,})(.*)', lines[index])
        if not match:
            index += 1
            continue
        indent, fence, info = match.groups()
        if include_prose and prose_start < offsets[index]:
            result.units.append(Unit(f'prose:{prose_start}', text[prose_start:offsets[index]], prose_start, offsets[index]))
        end = index + 1
        while end < len(lines) and not re.match(r'^\s*' + re.escape(fence[0]) + '{' + str(len(fence)) + r',}\s*$', lines[end]):
            end += 1
        kotlin = bool(re.search(r'(^|[\s{.="\'])kotlin\b', info))
        if kotlin:
            if end == len(lines):
                result.findings.append(issue('unterminated-fence', f'line {index + 1}'))
            else:
                raw = lines[index + 1:end]
                # Dedent only the indentation belonging to the container, then restore it exactly.
                if any(line.strip() and not line.startswith(indent) for line in raw):
                    result.findings.append(issue('fence-indentation', f'line {index + 1}'))
                else:
                    wrapped = info.strip().split(maxsplit=1)[0] in {'diffstatic', 'diffstatic_runner', 'diff', 'diff_runner'}
                    # Before and after are separate programs; fold markers can occur inside braces.
                    starts = [index + 1]
                    if wrapped:
                        starts += [n for n in range(index + 2, end) if lines[n].strip() in {':::before', ':::after'}]
                    for first, last in zip(starts, starts[1:] + [end]):
                        result.units.append(_markdown_code_unit(lines[first:last], indent, f'fence:{index + 1}:{first + 1}',
                                                               offsets[first], offsets[last], wrapped))
        index = end + 1
        prose_start = offsets[min(index, len(lines))]
    if include_prose and prose_start < len(text):
        result.units.append(Unit(f'prose:{prose_start}', text[prose_start:], prose_start, len(text)))
    return result


def yaml_units(text: str, properties: set[str]) -> Extraction:
    import yaml
    from yaml.nodes import MappingNode, ScalarNode, SequenceNode
    result = Extraction()
    # compose decodes scalars but never constructs arbitrary tagged Python objects.
    try:
        root = yaml.compose(text, Loader=yaml.SafeLoader)
    except yaml.YAMLError as error:
        mark = getattr(error, 'problem_mark', None)
        location = f' at line {mark.line + 1}' if mark else ''
        raise ContainerError(f'invalid YAML{location}: {getattr(error, "problem", type(error).__name__)}') from error
    seen = set()

    def scalar_unit(node, identity):
        if not isinstance(node, ScalarNode) or node.tag != 'tag:yaml.org,2002:str':
            result.findings.append(issue('unsupported-yaml-code', identity))
            return
        start, end = node.start_mark.index, node.end_mark.index
        def encode(value):
            if node.style == '|':
                raw = text[start:end]
                head, sep, _ = raw.partition('\n')
                content_lines = text[start + len(head) + len(sep):end].splitlines()
                indents = [len(line) - len(line.lstrip(' ')) for line in content_lines if line.strip()]
                if indents:
                    pad = ' ' * min(indents)
                    newline = '\r\n' if '\r\n' in raw else '\n'
                    # Preserve header/chomping/comments. Syntax-only edits preserve newline count.
                    rendered = head.rstrip('\r') + newline + ''.join(pad + line + newline for line in value.splitlines())
                    return rendered
            # Quoted/flow/folded scalar: a JSON string is also a lossless YAML string.
            return quoted(value)
        result.units.append(Unit(identity, node.value, start, end, encode))

    def walk(node, path='yaml', depth=0):
        if node is None:
            return
        if depth > 100 or id(node) in seen:
            raise ContainerError('YAML aliases or excessive nesting require review')
        seen.add(id(node))
        if isinstance(node, MappingNode):
            pairs = {}
            for key, value in node.value:
                if not isinstance(key, ScalarNode):
                    raise ContainerError('non-scalar YAML key')
                if key.value in pairs:
                    raise ContainerError('duplicate YAML key')
                pairs[key.value] = value
            prop = pairs.get('property', pairs.get('prop'))
            language = pairs.get('code_language', pairs.get('mainLanguage', pairs.get('language')))
            is_kotlin = language is None or (isinstance(language, ScalarNode) and language.value.lower() in ('kotlin', 'kt'))
            for key, value in pairs.items():
                is_code = key in properties or (key in ('from', 'to', 'value') and isinstance(prop, ScalarNode) and prop.value in properties)
                if is_code and is_kotlin:
                    if id(value) in seen:
                        raise ContainerError('aliased YAML code requires review')
                    seen.add(id(value))
                    scalar_unit(value, f'{path}/{key}')
                else:
                    walk(value, f'{path}/{key}', depth + 1)
        elif isinstance(node, SequenceNode):
            for i, child in enumerate(node.value):
                walk(child, f'{path}/{i}', depth + 1)
    walk(root)
    return result


def qualified_name_properties(text: str, keys: set[str]) -> Extraction:
    """Explicitly registered Java-properties fields holding qualified Kotlin names.

    Literal key=value syntax only: reject escapes/continuations rather than misread Java's
    property semantics. Feed values through the existing import mapper, preserving all other bytes.
    """
    result = Extraction()
    seen = set()
    offset = 0
    for line in text.splitlines(keepends=True):
        if line.strip() and not line.lstrip().startswith(('#', '!')):
            match = re.fullmatch(r'[ \t]*([A-Za-z_][\w.-]*)[ \t]*=[ \t]*([^\r\n]*?)[ \t]*(?:\r?\n)?', line)
            if not match or '\\' in line:
                raise ContainerError('qualified-name properties require literal key=value lines, without escapes or continuations')
            key, value = match.group(1, 2)
            if key in seen:
                raise ContainerError(f'duplicate property: {key}')
            seen.add(key)
            if key in keys:
                if not re.fullmatch(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+', value):
                    raise ContainerError(f'{key} must contain a qualified Kotlin name')

                def encode(code):
                    if not re.fullmatch(r'import [A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+\n', code):
                        raise ContainerError('qualified-name mapping produced something other than one import')
                    return code.removeprefix('import ').removesuffix('\n')

                result.units.append(Unit(f'qualified-name:{key}', f'import {value}\n',
                                         offset + match.start(2), offset + match.end(2), encode, script=False))
        offset += len(line)
    if keys - seen:
        raise ContainerError(f'missing registered qualified-name properties: {sorted(keys - seen)}')
    return result


def extract(path: str, text: str, properties: set[str], *, workspace=False, qualified_names=None, markdown_prose=False) -> Extraction:
    if qualified_names:
        return qualified_name_properties(text, qualified_names)
    p = Path(path)
    if p.suffix in ('.kt', '.kts'):
        # Docs, templates, UI-regression fixtures and box sidecars use .kt for script fragments.
        # Compiled source sets live under src/ (including standalone tools' src/main/kotlin).
        script = workspace or p.suffix == '.kts' or p.name.endswith('_.kt') or 'src' not in p.parts
        return Extraction([Unit('source', text, 0, len(text), script=script)])
    if p.name == 'metadata.edn':
        return edn(text, properties)
    if p.suffix in ('.yaml', '.yml'):
        return yaml_units(text, properties)
    if p.suffix == '.md':
        return markdown(text, include_prose=markdown_prose)
    return Extraction()
