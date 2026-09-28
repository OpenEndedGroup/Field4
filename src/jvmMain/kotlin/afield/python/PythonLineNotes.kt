package afield.python

/**
 * The Python half of a Python box's line notes ([afield.stdlib.LastRunNotes]): the rewriter that
 * records, as a cell runs, what each top-level statement came to.
 *
 * [SOURCE] is spliced into the interpreter's startup text, and is kept here, whole and free of Kotlin
 * templates, so `PythonLineNotesTest` can run exactly this text under a plain `python3` without JEP.
 * It defines one name, `_field_line_notes`, a namespace with:
 *
 * - `instrument(mod, key, capture_name, source)` — rewrites a parsed cell after the last-value capture
 *   (so the value the cell returns is unchanged) and returns the name the recorder must be bound to
 *   while the cell runs, or `None` when nothing was rewritten. The name is fresh and occurs nowhere in
 *   the cell's source, so nothing the cell assigns can break the recorder; the caller binds it only
 *   for the duration of the run (`bind`). A leading docstring and the `from __future__` imports after
 *   it are left alone and unrecorded: Python requires those to come first.
 * - `take(key)` — what the recorder holds for `key`, as JSON, emptied.
 * - `describe(value)` — a value's `repr`, but only for builtin scalars, short strings and small
 *   containers of them. Anything else is described by its type name alone: a note never calls a user
 *   type's `__repr__`, which may be slow (a large DataFrame, a GPU tensor) or have side effects.
 *
 * `AFIELD_PYTHON_LINE_NOTES=0` (or `false`, `off`, `no`) in the environment turns the rewriter off:
 * `instrument` changes nothing and returns `None`.
 */
object PythonLineNotes {
    /** Whether Python boxes record line notes; see the class KDoc for the switch. */
    val enabled: Boolean
        get() = System.getenv("AFIELD_PYTHON_LINE_NOTES")?.trim()?.lowercase() !in setOf("0", "false", "off", "no")

    /** The rewriter, as Python source. See the class KDoc. */
    val SOURCE: String = """
# ---------- line notes: what each top-level statement came to, recorded as it runs ----------
def _field_line_notes_factory():
    import ast as _ast
    import os as _os
    import json as _json
    import secrets as _secrets
    import types as _types

    enabled = _os.environ.get("AFIELD_PYTHON_LINE_NOTES", "1").strip().lower() not in ("0", "false", "off", "no")
    notes = {}
    scalars = (int, float, bool, complex, type(None))
    texts = (str, bytes)
    containers = (list, tuple, set, frozenset, dict)
    limit = 100

    def cut(text):
        return text if len(text) <= limit else text[:limit - 1] + "…"

    def plain(x):
        t = type(x)
        return t in scalars or (t in texts and len(x) <= limit)

    # A value's repr when it is a builtin one we can print without running user code; else None.
    def describe(value):
        t = type(value)
        if t in scalars:
            return cut(repr(value))
        if t in texts:
            return cut(repr(value[:limit])) if len(value) > limit else repr(value)
        if t in containers and len(value) <= 8:
            items = [x for kv in value.items() for x in kv] if t is dict else list(value)
            if all(plain(x) for x in items):
                return cut(repr(value))
        return None

    def record(key, first, last, value, kind):
        try:
            text = str(value) if kind == "ran" else describe(value)
            notes.setdefault(key, []).append([first, last, kind, type(value).__name__, text])
        except Exception:
            pass
        return None

    def take(key):
        return _json.dumps(notes.pop(key, []))

    def instrument(mod, key, capture_name, source):
        if not enabled:
            return None
        name = "__field_line_note_" + _secrets.token_hex(6) + "__"
        while name in source:
            name = "__field_line_note_" + _secrets.token_hex(6) + "__"
        start = 0
        body = mod.body
        # Python insists a docstring and then the __future__ imports come first; leave them alone.
        if body and isinstance(body[0], _ast.Expr) and isinstance(body[0].value, _ast.Constant) and isinstance(body[0].value.value, str):
            start = 1
        while start < len(body) and isinstance(body[start], _ast.ImportFrom) and body[start].module == "__future__":
            start += 1
        out = list(body[:start])
        for st in body[start:]:
            first = st.lineno
            last = getattr(st, "end_lineno", None) or st.lineno
            def note(value, kind):
                call = _ast.Expr(value=_ast.Call(
                    func=_ast.Name(id=name, ctx=_ast.Load()),
                    args=[_ast.Constant(key), _ast.Constant(first), _ast.Constant(last), value, _ast.Constant(kind)],
                    keywords=[]))
                _ast.copy_location(call, st)
                _ast.copy_location(call.value, st)
                return call
            out.append(st)
            if isinstance(st, _ast.Expr):
                # the value itself, evaluated once: the expression becomes the recorder's argument
                out.pop()
                out.append(note(st.value, "value"))
            elif isinstance(st, _ast.Assign) and isinstance(st.targets[0], _ast.Name):
                kind = "value" if st.targets[0].id == capture_name else "assigned " + st.targets[0].id
                out.append(note(_ast.Name(id=st.targets[0].id, ctx=_ast.Load()), kind))
            elif isinstance(st, (_ast.AnnAssign, _ast.AugAssign)) and isinstance(st.target, _ast.Name) and getattr(st, "value", None) is not None:
                out.append(note(_ast.Name(id=st.target.id, ctx=_ast.Load()), "assigned " + st.target.id))
            elif isinstance(st, (_ast.FunctionDef, _ast.AsyncFunctionDef, _ast.ClassDef)):
                out.append(note(_ast.Name(id=st.name, ctx=_ast.Load()), "defines " + st.name))
            else:
                out.append(note(_ast.Constant(type(st).__name__.lower()), "ran"))
        mod.body = out
        return name

    # Bind the recorder under [name] in [scope] for the length of a run, and take it away after.
    class bind:
        def __init__(self, scope, name):
            self.scope = scope
            self.name = name
        def __enter__(self):
            if self.name:
                self.scope[self.name] = record
            return self
        def __exit__(self, *exc):
            if self.name:
                self.scope.pop(self.name, None)
            return False

    return _types.SimpleNamespace(enabled=enabled, instrument=instrument, take=take, describe=describe, record=record, bind=bind)

_field_line_notes = _field_line_notes_factory()
del _field_line_notes_factory
"""
}
