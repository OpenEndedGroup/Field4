"""Review-only diffs; never reserialize or change the migration's source blobs."""
import difflib
import re


def unified_text(before, after, source, destination):
    # difflib leaves unterminated input lines unterminated in its output too.
    # Joining those directly hides the following addition inside the deletion.
    return ''.join(line if line.endswith('\n') else line + '\n\\ No newline at end of file\n'
                   for line in difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                                    fromfile=source, tofile=destination))


def compact_changes(before, after):
    """Bounded context around edits in large single-line containers, not a patch."""
    if max(len(before), len(after)) < 1000 or max(len(before), len(after)) > 200_000:
        return None
    if len(before.splitlines()) != 1 or len(after.splitlines()) != 1:
        return None
    tokens = [re.findall(r'\w+|[^\w]', value) for value in (before, after)]
    if max(map(len, tokens)) > 30_000:
        return None
    offsets = []
    for parts in tokens:
        positions = [0]
        for part in parts:
            positions.append(positions[-1] + len(part))
        offsets.append(positions)
    def excerpt(value, start, end):
        lo, hi = max(0, start - 65), min(len(value), end + 65)
        return ('…' if lo else '') + value[lo:hi].replace('\n', '\\n').replace('\r', '\\r') + ('…' if hi < len(value) else '')
    edits = []
    for kind, i, j, k, l in difflib.SequenceMatcher(None, *tokens, autojunk=False).get_opcodes():
        if kind != 'equal':
            a, b = offsets[0][i], offsets[0][j]
            c, d = offsets[1][k], offsets[1][l]
            edits.append({'before': excerpt(before, a, b), 'after': excerpt(after, c, d)})
    return edits or None
