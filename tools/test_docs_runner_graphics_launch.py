"""The budget the docs runner gives its own renderer bring-up, as distinct from the fence's.

A `runner` fence's `timeout` is what the page author allows *their example*. Until 2026-09-16 it
was also threaded into the three commands `docs_hooks.build_backdoor_command` generates to bring
up the native renderer — `macro write/run graphics_renderer_launch` and `wait-native-renderer` —
so one attribute governed both an author's code and a cold Vulkan boot whose cost is a property
of the machine.

`afield_four_graphics.md` declares `timeout="10"` on all ten of its fences, below even the 45.0
default and the tightest native-window budget in the corpus. That killed three consecutive
certifying docs builds at capture 3–8 of ~544 with a `TimeoutError` that *moved* between runs on
a byte-identical tree — so no fence was broken and no page was at fault; the budget was simply
insufficient wherever it landed.

These tests pin the floor and, more usefully, pin the *corpus property* that motivated it: no
graphics-context page may sit below the floor for its bring-up, whatever it declares for itself.
"""

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_ROOT = REPO_ROOT / "public" / "docs"

# The page whose budget caused the outage, kept explicit so a test failure names it.
TIGHTEST_GRAPHICS_PAGE = DOCS_ROOT / "afield_four_graphics.md"


class _Options:
    """Just the one attribute `graphics_launch_timeout` reads.

    `RunnerOptions` is a large frozen dataclass with no defaults; constructing it here would
    couple this test to every unrelated field added to it later.
    """

    def __init__(self, timeout: float):
        self.timeout = timeout


class GraphicsLaunchTimeoutTests(unittest.TestCase):

    def test_a_tight_fence_is_floored(self):
        """The 10s budget that aborted the bring-up is raised to the floor."""
        self.assertEqual(
            docs_hooks.graphics_launch_timeout(_Options(10.0)),
            docs_hooks.GRAPHICS_LAUNCH_TIMEOUT_FLOOR_SECONDS,
        )

    def test_the_unspecified_default_is_floored(self):
        """45.0 is what a fence gets by declaring nothing, and it is still below the floor."""
        self.assertEqual(
            docs_hooks.graphics_launch_timeout(_Options(45.0)),
            docs_hooks.GRAPHICS_LAUNCH_TIMEOUT_FLOOR_SECONDS,
        )

    def test_a_generous_fence_is_honoured_not_clamped(self):
        """The floor raises; it never lowers.

        A page whose example needs 600s needs its renderer to outlive the example, so the floor
        must not become a ceiling.
        """
        self.assertEqual(docs_hooks.graphics_launch_timeout(_Options(600.0)), 600.0)

    def test_the_floor_is_an_existing_authored_budget(self):
        """90s is `puppet_browser.md`'s own number for booting a native window and waiting on it.

        Pinned so the floor stays traceable to a working measurement rather than becoming a
        number nobody can defend.
        """
        self.assertEqual(docs_hooks.GRAPHICS_LAUNCH_TIMEOUT_FLOOR_SECONDS, 90.0)
        page = (DOCS_ROOT / "puppet_browser.md").read_text()
        self.assertIn('timeout="90"', page)


class GraphicsCorpusBudgetTests(unittest.TestCase):
    """The corpus property the floor exists to guarantee."""

    def _graphics_pages(self) -> dict[Path, list[float]]:
        pages: dict[Path, list[float]] = {}
        for path in sorted(DOCS_ROOT.rglob("*.md")):
            text = path.read_text(errors="replace")
            if "graphics-context" not in text and "graphics_context" not in text:
                continue
            pages[path] = [float(m) for m in re.findall(r'timeout="([0-9.]+)"', text)]
        return pages

    def test_the_corpus_has_graphics_pages_to_check(self):
        """A discovery bug must not make this suite vacuously green."""
        self.assertGreater(len(self._graphics_pages()), 5)

    def test_no_graphics_page_boots_its_renderer_below_the_floor(self):
        """Whatever a page declares, its bring-up gets at least the floor.

        This is the assertion that would have caught the outage at authoring time: the page that
        broke the build declared a budget legal for its example and far too tight for a cold
        renderer boot, and nothing anywhere said those were different things.
        """
        floor = docs_hooks.GRAPHICS_LAUNCH_TIMEOUT_FLOOR_SECONDS
        for path, declared in self._graphics_pages().items():
            for fence_timeout in declared or [45.0]:
                effective = docs_hooks.graphics_launch_timeout(_Options(fence_timeout))
                self.assertGreaterEqual(
                    effective,
                    floor,
                    f"{path.relative_to(REPO_ROOT)} declares timeout={fence_timeout} and would "
                    f"boot its renderer on {effective}s",
                )

    def test_the_page_that_broke_the_build_is_still_the_tight_one(self):
        """Kept as documentation: the 10s fences are still there and are still floored.

        If someone edits that page's budgets, this test failing is the prompt to re-read why the
        floor exists rather than to assume it is obsolete.
        """
        text = TIGHTEST_GRAPHICS_PAGE.read_text()
        self.assertIn('timeout="10"', text)
        self.assertEqual(
            docs_hooks.graphics_launch_timeout(_Options(10.0)),
            docs_hooks.GRAPHICS_LAUNCH_TIMEOUT_FLOOR_SECONDS,
        )


if __name__ == "__main__":
    unittest.main()
