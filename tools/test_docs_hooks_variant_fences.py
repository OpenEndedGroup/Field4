"""A `variant=true` diff fence shows a side road without moving the road.

A tutorial page builds one program fence by fence, each `diff` inheriting its `:::before` from the
fence above. A variant (what happens with a different radius, without a filter) inherits like any
other fence, but the next step must continue from the main line, not from the variant.
"""

import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "public"))

import docs_hooks  # noqa: E402


def fences(markdown: str) -> list[str]:
    """The body of each fence once the hooks have rewritten the page (inherited `:::before` filled in)."""
    with tempfile.TemporaryDirectory() as temporary:
        docs = Path(temporary)
        state = SimpleNamespace(docs_dir=docs, repo_root=REPO_ROOT)
        out = docs_hooks.process_markdown(markdown=markdown, page_path=docs / "page.md", state=state, files=[])
    return [part.split("\n```", 1)[0] for part in out.split("```diff")[1:]]


PAGE = """
```diffstatic {data-lang=kotlin}
val radius = 10
```

```diff {data-lang=kotlin variant=true}
:::after
val radius = 99
```

```diff {data-lang=kotlin}
:::after
val radius = 10
draw(radius)
```
"""


class VariantFenceTests(unittest.TestCase):
    def test_a_variant_inherits_from_the_fence_above(self):
        variant = fences(PAGE)[1]
        self.assertIn(":::before\nval radius = 10\n:::after\nval radius = 99", variant)

    def test_the_fence_after_a_variant_inherits_from_the_main_line(self):
        step = fences(PAGE)[2]
        self.assertIn(":::before\nval radius = 10\n:::after\nval radius = 10\ndraw(radius)", step)
        self.assertNotIn("99", step)

    def test_without_the_attribute_the_next_fence_inherits_the_variant(self):
        step = fences(PAGE.replace(" variant=true", ""))[2]
        self.assertIn(":::before\nval radius = 99\n:::after", step)

    def test_only_a_true_value_makes_a_variant(self):
        self.assertTrue(docs_hooks.fence_is_variant(open_line="```diff {data-lang=kotlin variant=true}", fence_name="diff"))
        self.assertTrue(docs_hooks.fence_is_variant(open_line='```diff_runner {variant="true" video="a.mp4"}', fence_name="diff_runner"))
        self.assertFalse(docs_hooks.fence_is_variant(open_line="```diff {data-lang=kotlin variant=false}", fence_name="diff"))
        self.assertFalse(docs_hooks.fence_is_variant(open_line="```diff {data-lang=kotlin}", fence_name="diff"))


if __name__ == "__main__":
    unittest.main()
