"""Field's main languages, for the harnesses that have to set one up.

One constant, in one place, because two harnesses need it and they used to disagree. A box written
in a main language is a *child* of a box carrying `mainLanguage`
(`src/jvmMain/kotlin/afield/runtime/LanguageRuntimeSupport.kt` — `applyRuntimeToggle` walks
`root.children`, so the root itself stays Kotlin), which means any harness that runs a snippet in
one of these languages has to create two boxes and connect them rather than one. `public/docs_hooks.py`
does that for a `diffstatic_runner` fence and `tools/run_field_tests.py` for a FIELD_TEST, and a
language missing from either list fails the same confusing way: the snippet is run as Kotlin and
reports a compile error about its first line.

Membership is "a language whose boxes need a language root", not "every registered main language".
`remotejs` and `kotlinjs` are registered main languages that are absent deliberately — those need a
browser or a remote head attached, which is not something either harness can arrange by creating a
box. Add a language here once running a snippet in it needs nothing but a root and a child.

`pd` (Pure Data, `afield.pd.PdExecution`) needs libpd built as well, which is a native
prerequisite rather than something a harness arranges: a fence declares it with
`requires="libpd"`, and without the library the box reports how to build it. `lua`
(`afield.lua.LuaExecution`, over LuaJIT) is the same shape: `requires="luajit"`, and without LuaJIT
the box says `brew install luajit`.
"""

from __future__ import annotations

MAIN_LANGUAGE_ROOT_LANGUAGES: tuple[str, ...] = ("fieldml", "python", "datalog", "clojure", "pd", "lua")


def needs_language_root(language: str | None) -> bool:
    """Whether a snippet in `language` has to be run in a child of a `mainLanguage` root."""
    return (language or "").strip().lower() in MAIN_LANGUAGE_ROOT_LANGUAGES
