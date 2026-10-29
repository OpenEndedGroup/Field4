"""The docs runner's committed inputs still produce what its captures wait for.

`public/docs_hooks.py` gives its Field a fresh copy of `tools/docs_runner_inputs/generated_completion_docs.json`
as the KDoc drafts cache, and the ai_doc_polish page imports those drafts and waits for the queue to count
them. A draft stops importing once its declaration gains KDoc or disappears, and the capture would then
time out an hour into a build; this fails at landing instead.
"""

import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))

import docs_notes as dn  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
DRAFTS = REPO / "tools" / "docs_runner_inputs" / "generated_completion_docs.json"


class CommittedDraftsTest(unittest.TestCase):
    def test_every_committed_draft_imports_against_this_tree(self) -> None:
        with TemporaryDirectory() as tmp:
            store = dn.Store(Path(tmp) / "store.json")
            skipped: list[str] = []
            counts = dn.import_drafts(store, dn.Git(REPO), DRAFTS, log=skipped.append)
        records = len(json.loads(DRAFTS.read_text(encoding="utf-8"))["records"])
        self.assertEqual(
            counts["imported"], records,
            f"{DRAFTS.relative_to(REPO)} has drafts that no longer import ({counts}; {skipped}). Replace each "
            "with a real draft for a declaration under src/ that still has no KDoc: copy its record from "
            ".field/filestore/generated_completion_docs.json after completion has drafted one.",
        )


if __name__ == "__main__":
    unittest.main()
