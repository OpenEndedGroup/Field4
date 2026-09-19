"""Regression guard for `check_doc_offline_links.py`, the offline-viewer link shape lint.

Background, measured 2026-09-17 in this tree: 137 `offline.html?file=…` references on 17 of the
513 tracked markdown files under `public/docs` were written `/offline.html?file=/field4/…`.
Both halves are 404s on the published site — `site_url` is `http://openendedgroup.com/Field4/`,
so `/offline.html` resolves outside the prefix, and the viewer's own `?file=` candidate ladder
(`offline-viewer.js:ednFileCandidates`) misses on all three candidates for a `/field4/…` value
under a `/Field4/` mount. `check_doc_site_links.py` catches the first half once its grandfather
entry is gone, and structurally cannot catch the second: it strips `?…` before resolving.

What is pinned here, because each was a way this guard could have passed for the wrong reason:

* the scan root enumerates top-level pages. A git pathspec `'public/docs/**/*.md'` matches no
  single-level path, so the obvious spelling would have excluded all 17 carriers and reported a
  clean zero.
* an empty or shrunken population fails rather than passing. `RECORDED_OCCURRENCES` is a floor.
* the regex matches the *wrong* shapes, not just the right one. A lint whose pattern only
  matches correct output selects nothing and always passes.
* `CARRIER_PAGES` is checked for set equality in both directions, so a removed page fails as
  loudly as a new one.

Run with:  uv run --group backdoor python -m unittest tools.test_check_doc_offline_links
"""

import re
import subprocess
import sys
import unittest
from pathlib import Path

from tools.check_doc_offline_links import (
    CARRIER_PAGES,
    OFFLINE_REF,
    RECORDED_OCCURRENCES,
    prefix_from_site_url,
    problems_for,
    read_config,
    tracked_docs_markdown,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
CHECKER = REPO_ROOT / "tools" / "check_doc_offline_links.py"
DOCS_DIR = REPO_ROOT / "public" / "docs"
CONFIG = REPO_ROOT / "public" / "zensical.yml"

GOOD_FILE = "assets/agent_transcripts/hello_display/metadata.edn"


class Premise(unittest.TestCase):
    """The facts the guard's conclusion rests on, asserted so an environment change fails loudly."""

    def test_the_site_is_published_under_a_prefix_that_differs_from_site_dir(self) -> None:
        site_url, site_dir = read_config(CONFIG)
        prefix = prefix_from_site_url(site_url)
        self.assertEqual(prefix, "/Field4/")
        self.assertEqual(site_dir, "field4")
        # The whole failure mode is this pair differing, and differing only in case: every one of
        # the 137 links worked in local preview and 404s on a case-sensitive host. If they are ever
        # made to agree, this guard is measuring something else and should be re-read.
        self.assertNotEqual(prefix.strip("/"), site_dir)
        self.assertEqual(prefix.strip("/").lower(), site_dir.lower())

    def test_the_scan_root_finds_top_level_pages(self) -> None:
        pages = tracked_docs_markdown(REPO_ROOT)
        # 513 tracked markdown files on 2026-09-17; asserted as a floor so growth is fine but a
        # pathspec that silently drops the top level (where every carrier lives) is not.
        self.assertGreaterEqual(len(pages), 500)
        top_level = [p for p in pages if p.count("/") == 2]
        self.assertGreaterEqual(len(top_level), 100)
        self.assertIn("public/docs/agent_transcripts.md", top_level)

    def test_the_viewer_resolves_file_against_its_own_url(self) -> None:
        """The reason a relative `?file=` works: the viewer's first candidate is the value itself."""
        viewer = REPO_ROOT / "src" / "commonMain" / "resources" / "offline-viewer.js"
        source = viewer.read_text(encoding="utf-8")
        self.assertIn("function ednFileCandidates", source)
        self.assertIn("add(fileParam)", source)


class Pattern(unittest.TestCase):
    """The regex has to match malformed references, or the lint selects nothing."""

    def test_matches_the_old_server_absolute_shape(self) -> None:
        text = f"[Open ↗](/offline.html?file=/field4/{GOOD_FILE})"
        match = OFFLINE_REF.search(text)
        self.assertIsNotNone(match)
        self.assertEqual(match.group("lead"), "/")
        self.assertEqual(match.group("file"), f"/field4/{GOOD_FILE}")

    def test_matches_the_corrected_shape(self) -> None:
        match = OFFLINE_REF.search(f"[Open ↗](offline.html?file={GOOD_FILE})")
        self.assertIsNotNone(match)
        self.assertEqual(match.group("lead"), "")
        self.assertEqual(match.group("file"), GOOD_FILE)

    def test_matches_a_raw_html_src(self) -> None:
        match = OFFLINE_REF.search(f'<iframe src="offline.html?file={GOOD_FILE}"></iframe>')
        self.assertIsNotNone(match)
        self.assertEqual(match.group("lead"), "")
        self.assertEqual(match.group("file"), GOOD_FILE)


class Predicate(unittest.TestCase):
    """Each half of the rule, and the shapes that broke."""

    def problems(self, lead: str, file_value: str) -> list[str]:
        return problems_for(
            page="probe.md", lead=lead, file_value=file_value,
            site_dir="field4", docs_dir=DOCS_DIR,
        )

    def test_the_corrected_shape_is_clean(self) -> None:
        self.assertEqual(self.problems("", GOOD_FILE), [])

    def test_the_shape_that_shipped_fails_on_both_halves(self) -> None:
        found = self.problems("/", f"/field4/{GOOD_FILE}")
        self.assertEqual(len(found), 3, found)
        self.assertTrue(any("server-absolute (`/offline.html`)" in p for p in found))
        self.assertTrue(any("?file=/field4/" in p and "server-absolute" in p for p in found))
        self.assertTrue(any("site_dir" in p for p in found))

    def test_a_climbing_link_fails(self) -> None:
        found = self.problems("../", GOOD_FILE)
        self.assertEqual(len(found), 1, found)
        self.assertIn("climbs", found[0])

    def test_an_uncommitted_target_fails(self) -> None:
        found = self.problems("", "assets/agent_transcripts/not_a_transcript/metadata.edn")
        self.assertEqual(len(found), 1, found)
        self.assertIn("names nothing committed", found[0])

    def test_the_site_dir_segment_is_rejected_case_insensitively(self) -> None:
        # `/Field4/assets/…` is what someone "fixing the case" would write. It is still wrong:
        # the site dir is the root when published, so the segment must not appear at all.
        self.assertTrue(any("site_dir" in p for p in self.problems("", "Field4/" + GOOD_FILE)))


class AgainstTheRealTree(unittest.TestCase):
    """End to end on this repository, which is the only population the ledger describes."""

    def test_exit_zero_and_the_recorded_population(self) -> None:
        result = subprocess.run(
            [sys.executable, str(CHECKER), "--summary"],
            capture_output=True, text=True, cwd=str(REPO_ROOT),
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("are viewer-relative on both halves", result.stdout)
        # A FLOOR, not equality — which is what the checker itself enforces, and says so in the
        # comment above RECORDED_OCCURRENCES: `agent_transcripts.md` gains one reference per new
        # transcript, so the recorded number goes stale the next time anyone adds one. This line
        # read `assertIn(f"{RECORDED_OCCURRENCES} …")` until 2026-09-19 and went red the moment
        # the sixteen-demo promotion sweep took the tree from 137 references to 156 — a guard
        # failing for the one reason its own subject says is fine, and red in `check` (via
        # `checkPythonTools`) from that commit onwards.
        found = re.search(r"(\d+) `offline\.html\?file=` reference\(s\)", result.stdout)
        self.assertIsNotNone(found, result.stdout)
        self.assertGreaterEqual(int(found.group(1)), RECORDED_OCCURRENCES, result.stdout)
        self.assertIn(f"on {len(CARRIER_PAGES)} page(s)", result.stdout)

    def test_the_carrier_ledger_is_exactly_the_measured_set(self) -> None:
        pages = tracked_docs_markdown(REPO_ROOT)
        measured = {
            rel for rel in pages
            if OFFLINE_REF.search((REPO_ROOT / rel).read_text(encoding="utf-8"))
        }
        self.assertEqual(measured, CARRIER_PAGES)

    def test_no_authored_page_writes_the_old_shape_any_more(self) -> None:
        """Set equality is not enough on its own: this is the direct statement of the fix."""
        listing = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "ls-files", "public/docs"],
            capture_output=True, text=True, check=True,
        ).stdout
        offenders = []
        for rel in (line for line in listing.split("\n") if line.endswith(".md")):
            text = (REPO_ROOT / rel).read_text(encoding="utf-8")
            if "/offline.html" in text or "offline.html?file=/" in text:
                offenders.append(rel)
        self.assertEqual(offenders, [])


if __name__ == "__main__":
    unittest.main()
