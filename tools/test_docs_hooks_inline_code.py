"""Prose inline code: which spans are Kotlin-highlighted, and which stay plain.

The decision (Marc, 2026-10-01, handoffs 400854a2 / b4bdc0a0) is that every inline `code` span in
docs prose gets the reference tables' highlighting, Kotlin by default, except things that are not
code. `inline_code_language` is that classifier and `mark_inline_code_html` applies it to a built
page; the colouring itself is `highlightReferences()` in code-diff-player.js. Both directions are
pinned here, because the failure that matters most is a path or a flag coming out in colour.
"""

import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "public"))

from docs_hooks import (  # noqa: E402
    INLINE_CODE_MARK,
    inline_code_language,
    mark_inline_code_html,
)

MARKED = f"<code {INLINE_CODE_MARK}>"

HIGHLIGHTED = [
    # The two spans the handoffs were filed about.
    "foo(1,2,3.4).bar",
    "foo.bar(10.0)",
    "ramp(start, end)",
    "FaustLaunch.press(label)",
    "compute(0, …)",
    "voices = 8",
    # Calls, members, declarations, lambdas, infix calls.
    "call()",
    "box.frame",
    "Img.Companion",
    "fun Img.rotate90(): Img",
    "val heights: DoubleArray",
    "const val SUBMIT_TO_ENCODED",
    "data class Foo(val x: Int)",
    "graphMetrics { node -> cost }",
    "(Double) -> Double",
    "0 until state.size",
    "gain(0.6) then lowPass(0.08)",
    "x = -100",
    "ok = false",
    "people.cast<People>().age",
    'pat("bd sd hh hh")',
    "moduleHere { ... }",
    ".toFloat()",
    "if (width <= 0)",
    "math.log(x)",
    "mutex.lock",
    "@file:CompletionCategory",
]

PLAIN = [
    # Single bare words, identifiers, keywords, hashes, numbers, versions.
    "frame",
    "FLine",
    "val",
    "Int?",
    "@last",
    "601f63d73",
    "0.5",
    "2.5.0",
    "28 / 28 / 29",
    # Paths, file names, globs, extensions.
    "src/jvmMain/kotlin/afield/Boxes.kt",
    "tools/test_*.py",
    "dev_docs/agents/",
    "/field-docs/",
    "~/Dirt-Samples",
    "./field.sh",
    "metadata.edn",
    "Boxes.kt:54",
    "628f3f96-42d0-48dd-93ea-ea4d9f03b865_code_.kt",
    ".kt",
    "*.md",
    # URLs and hosts.
    "https://localhost:9092/index.html?file=firstDocument",
    "file://",
    "localhost:9092",
    "127.0.0.1:8765",
    "openendedgroup.com",
    "field.attacker.example",
    "Host: localhost",
    "Origin: null",
    "from:",
    "why <box> <name>",
    "2^depth",
    "com.microsoft.onnxruntime:onnxruntime",
    # Flags, commands, environment, options, key chords.
    "--workspace",
    "-d",
    "kill -INT",
    "./gradlew runJvm",
    "git merge main",
    "uv run --project . --group backdoor python tools/run_python_tests.py",
    "GET /v1/representations/{id}/waveform",
    "AFIELD_OFFLINE=1",
    "FIELD_DOCS_RUNNER_MODE=never",
    "audio=",
    "Ctrl+Space",
    "Alt+D",
    "Shift-Enter",
    "new-box",
    "toggle-drawing-layer",
    "wait-output-channel=",
    # Markup.
    '<div>hello</div>',
    "<code>",
    # The site's other notations.
    ":version 2",
    "#afield/rect [x y w h]",
    '{ :id "...", :properties { ... } }',
    "lo hi x -- c",
    "signal lo hi -- signal",
    "uv 5.0 * time 0.12 * + fbm palette saturate",
    "animateFor seconds f",
    "sin 0.0",
    'sync "/osc*/your/address"',  # Sonic Pi's Ruby: a name applied to a string
    "pre -[ move => count ]-> post",
    "multiply(?x, 1) => ?x",
    "ADD(MUL(linear[0.0=0.0 4.0=4.0], ?gain=1.0), ?bias=0.0)",
    "('a -> 'b) -> ['a] -> ['b]",
    "Float -> Float -> Float",
    "let f x = …",
    "7 |> mod 4",
    "(display channel v)",
    "->polar",
    "bd sd, hh*4",
    "[bd sd]*2",
    "fiddle~",
    "{{ stack.auto.program }}",
    "$HOME",
    # Maths and prose.
    "x̂ × ẑ = −ŷ",
    "b → c → d → b",
    "outWidth x outHeight",
    "a b",
    "TetgenException: ... (status 1): a closed surface needs at least 4 vertices",
]


class InlineCodeClassifierTests(unittest.TestCase):
    def test_code_is_kotlin(self):
        for text in HIGHLIGHTED:
            with self.subTest(text=text):
                self.assertEqual(inline_code_language(text), "kotlin")

    def test_what_is_not_code_stays_plain(self):
        for text in PLAIN:
            with self.subTest(text=text):
                self.assertIsNone(inline_code_language(text))

    def test_a_flag_or_url_inside_a_string_literal_does_not_count(self):
        self.assertEqual(inline_code_language('open("https://example.com")'), "kotlin")
        self.assertEqual(inline_code_language('args("--workspace")'), "kotlin")


def page(body: str) -> str:
    return (
        "<html><head><script>if (a < b) { x = '<code>f(x)</code>' }</script></head><body>"
        '<nav class="md-nav"><code>nav.item()</code></nav>'
        f'<article class="md-content__inner md-typeset">{body}</article>'
        "<footer><code>after.article()</code></footer></body></html>"
    )


class MarkInlineCodeHtmlTests(unittest.TestCase):
    def test_prose_code_is_marked_and_paths_are_not(self):
        out = mark_inline_code_html(page(
            "<p>Call <code>foo.bar(10.0)</code> from <code>src/jvmMain/kotlin/afield/Boxes.kt</code>.</p>"
        ))
        self.assertIn(f"{MARKED}foo.bar(10.0)</code>", out)
        self.assertIn("<code>src/jvmMain/kotlin/afield/Boxes.kt</code>", out)

    def test_entities_are_decoded_before_classifying(self):
        out = mark_inline_code_html(page("<li><code>List&lt;Vec2&gt;(n)</code> and <code>&lt;div&gt;</code></li>"))
        self.assertIn(f"{MARKED}List&lt;Vec2&gt;(n)</code>", out)
        self.assertIn("<code>&lt;div&gt;</code>", out)

    def test_only_the_article_is_touched(self):
        out = mark_inline_code_html(page("<p><code>a.b()</code></p>"))
        self.assertIn("<code>nav.item()</code>", out)
        self.assertIn("<code>after.article()</code>", out)
        self.assertIn("'<code>f(x)</code>'", out)  # a script body is not markup

    def test_fences_reference_tables_and_classed_code_are_left_alone(self):
        body = (
            "<pre><code>foo.bar(1)</code></pre>"
            '<div class="field-reference" data-code-lang="fieldml"><table><tr><td><code>let x = 6 in x * 7</code></td></tr></table></div>'
            '<p><code class="highlight"><span class="k">val</span></code>'
            ' <code class="source-citation" title="x">Boxes.kt:54</code>'
            " <code><span>a.b()</span></code>"
            " <code>after.fence()</code></p>"
        )
        out = mark_inline_code_html(page(body))
        self.assertIn("<pre><code>foo.bar(1)</code></pre>", out)
        self.assertIn("<td><code>let x = 6 in x * 7</code></td>", out)
        self.assertIn('<code class="highlight">', out)
        self.assertIn('<code class="source-citation" title="x">', out)
        self.assertIn("<code><span>a.b()</span></code>", out)
        # Leaving a skipped container restores eligibility.
        self.assertIn(f"{MARKED}after.fence()</code>", out)

    def test_a_table_cell_and_a_heading_in_prose_are_prose(self):
        out = mark_inline_code_html(page(
            "<table><tr><td><code>clamp(c, 0.0, 1.0)</code></td></tr></table><h4><code>ramp(start, end)</code></h4>"
        ))
        self.assertIn(f"<td>{MARKED}clamp(c, 0.0, 1.0)</code></td>", out)
        self.assertIn(f"<h4>{MARKED}ramp(start, end)</code></h4>", out)

    def test_marking_is_idempotent_and_re_decided(self):
        once = mark_inline_code_html(page("<p><code>foo()</code> <code>--flag</code></p>"))
        self.assertEqual(mark_inline_code_html(once), once)
        # A span an earlier build marked, which the classifier now calls plain, is unmarked.
        stale = page(f"<p>{MARKED}--flag</code></p>")
        self.assertIn("<p><code>--flag</code></p>", mark_inline_code_html(stale))


if __name__ == "__main__":
    unittest.main()
