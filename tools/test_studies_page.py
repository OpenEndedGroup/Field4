"""Hold public/docs/studies.md's verb table to the verbs LiveStudy actually has.

The page lists the ten standard study verbs with the `LiveStudy` method each one calls. Both lists
live in `src/jvmMain/kotlin/afield/stdlib/LiveStudy.kt`: the `StudyVerb` enum (what a document's
`registerStudyTools` can offer) and the `LiveStudy` interface (one method per verb). A verb added,
renamed or removed there without the page following fails here, in both directions.

Run directly with:  uv run --project . --group backdoor python -m unittest tools.test_studies_page
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
PAGE = REPO_ROOT / "public/docs/studies.md"
SOURCE = REPO_ROOT / "src/jvmMain/kotlin/afield/stdlib/LiveStudy.kt"


def page_rows() -> list[tuple[str, str]]:
    """(verb, method) for each row of the table under '## The ten verbs'."""
    text = PAGE.read_text(encoding="utf-8")
    section = text.split("## The ten verbs", 1)[1].split("\n## ", 1)[0]
    rows = []
    for line in section.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 2 and cells[1].startswith("`") and cells[1].endswith("`"):
            rows.append((cells[0], cells[1].strip("`")))
    return rows


def live_study_methods() -> list[str]:
    source = SOURCE.read_text(encoding="utf-8")
    body = source.split("interface LiveStudy {", 1)[1].split("\n}", 1)[0]
    return re.findall(r"^\s*fun (\w+)\(q: StudyRequest\.", body, flags=re.MULTILINE)


def study_verbs() -> list[str]:
    source = SOURCE.read_text(encoding="utf-8")
    entries = re.search(r"enum class StudyVerb \{([^}]*)\}", source).group(1)
    return [e.strip() for e in entries.split(",") if e.strip()]


def constant(name: str) -> str:
    """`TryVariants` -> `TRY_VARIANTS`."""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()


class StudiesPageTest(unittest.TestCase):
    def test_the_table_names_every_live_study_method_in_order(self):
        rows = page_rows()
        self.assertEqual(live_study_methods(), [method for _, method in rows],
                         f"{PAGE.relative_to(REPO_ROOT)}'s verb table has drifted from LiveStudy's methods")

    def test_the_table_names_every_study_verb_in_order(self):
        rows = page_rows()
        self.assertEqual(study_verbs(), [constant(verb) for verb, _ in rows],
                         f"{PAGE.relative_to(REPO_ROOT)}'s verb table has drifted from the StudyVerb enum")

    def test_the_section_heading_counts_the_rows(self):
        self.assertEqual(10, len(page_rows()), "the heading says ten verbs")


if __name__ == "__main__":
    unittest.main()
