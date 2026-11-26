"""tools/publication_merge.py: a merge leaves every web publication's catalog entry and files from one side."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import publication_merge  # noqa: E402

PUB = "public/docs/assets/web_publications"


def git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=repo, check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True).stdout


def sha(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


class Repo:
    """A tiny repo with the real driver setup: web_publications/** is merge=field-generated (keep ours)."""

    def __init__(self, root: Path):
        self.root = root
        git(root, "init", "-q", "-b", "main")
        for key, value in (("user.name", "t"), ("user.email", "t@t"), ("commit.gpgsign", "false"),
                           ("merge.field-generated.driver", "true")):
            git(root, "config", key, value)
        self.write(".gitattributes", f"{PUB}/** merge=field-generated\n")

    def write(self, name: str, text: str) -> None:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def publish(self, name: str, capture: str, source_text: str | None = None) -> None:
        """Capture publication [name]: its output file, and its catalog entry recording that file's hash."""
        source = f"public/docs/assets/agent_transcripts/{name}/metadata.edn"
        if source_text is not None:
            self.write(source, source_text)
        out = f"assets/web_publications/{name}/outputs/0.html"
        self.write(f"public/docs/{out}", capture)
        catalog = self.catalog()
        catalog["documents"][f"assets/agent_transcripts/{name}/metadata.edn"] = {
            "file": f"assets/web_publications/{name}/document/metadata.edn", "capturedAt": capture,
            "files": {out: sha(capture)},
            "sources": {source: sha((self.root / source).read_text())},
        }
        self.write(f"{PUB}/catalog.json", json.dumps(catalog, indent=2, sort_keys=True) + "\n")

    def catalog(self) -> dict:
        path = self.root / PUB / "catalog.json"
        return json.loads(path.read_text()) if path.exists() else {"version": 1, "documents": {}}

    def commit(self, message: str) -> None:
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "-m", message)

    def inconsistent(self) -> list[str]:
        """Publications whose recorded file hashes do not match the working tree."""
        docs = self.root / "public/docs"
        return sorted(source for source, entry in self.catalog()["documents"].items()
                      if any(not (docs / f).is_file() or sha((docs / f).read_text()) != h
                             for f, h in entry["files"].items()))

    def capture(self, name: str) -> str:
        return (self.root / f"{PUB}/{name}/outputs/0.html").read_text()


class UnifyAfterMerge(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(Path(self.tmp.name))
        self.repo.publish("a", "a-v1", "a source")
        self.repo.publish("b", "b-v1", "b source")
        self.repo.commit("base")

    def tearDown(self):
        self.tmp.cleanup()

    def merge(self, branch: str) -> None:
        subprocess.run(["git", "merge", "-q", "--no-ff", "--no-commit", branch], cwd=self.repo.root,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_the_landing_split_of_2026_11_11(self):
        """The branch recaptured a; main recaptured only b. The keep-ours driver keeps main's
        catalog (both changed it) but takes the branch's a capture (only it changed that file)."""
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("a", "a-v2")
        r.commit("branch recaptures a")
        git(r.root, "switch", "-q", "main")
        r.publish("b", "b-v2")
        r.commit("main recaptures b")
        self.merge("topic")
        self.assertEqual(r.inconsistent(), ["assets/agent_transcripts/a/metadata.edn"],
                         "the split this tool exists to undo did not reproduce")
        taken = publication_merge.unify(r.root)
        self.assertEqual(taken, ["a"])
        self.assertEqual(r.inconsistent(), [])
        self.assertEqual((r.capture("a"), r.capture("b")), ("a-v2", "b-v2"))

    def test_a_deferral_travels_with_the_entry_the_merge_keeps(self):
        """docs_publications.py records a page whose digest-only refresh failed under `deferred`;
        when the merge takes their entry, their deferral comes too, and ours goes when ours is
        replaced."""
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("a", "a-v2")
        catalog = r.catalog()
        catalog["deferred"] = {"assets/agent_transcripts/b/metadata.edn": {"captureDigest": "v2"}}
        r.write(f"{PUB}/catalog.json", json.dumps(catalog, indent=2, sort_keys=True) + "\n")
        r.commit("branch recaptures a, defers b")
        git(r.root, "switch", "-q", "main")
        catalog = r.catalog()
        catalog["deferred"] = {"assets/agent_transcripts/a/metadata.edn": {"captureDigest": "v1"}}
        r.write(f"{PUB}/catalog.json", json.dumps(catalog, indent=2, sort_keys=True) + "\n")
        r.commit("main defers a")
        self.merge("topic")
        publication_merge.unify(r.root)
        # a: theirs changed the entry, so theirs (no deferral for a). b: neither changed its entry,
        # so ours, and ours records no deferral for b.
        self.assertNotIn("deferred", r.catalog())

    def test_a_new_publication_on_their_side_is_kept(self):
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("c", "c-v1", "c source")
        r.commit("branch adds c")
        git(r.root, "switch", "-q", "main")
        r.publish("b", "b-v2")
        r.commit("main recaptures b")
        self.merge("topic")
        publication_merge.unify(r.root)
        self.assertEqual(r.inconsistent(), [])
        self.assertIn("assets/agent_transcripts/c/metadata.edn", r.catalog()["documents"])
        self.assertEqual(r.capture("c"), "c-v1")

    def test_both_recaptured_keeps_the_side_captured_from_the_merged_sources(self):
        """A branch that recaptured a, merging a main that changed a's source and recaptured it: the
        branch's copy is stale against the merged tree, so main's (theirs) wins, whole."""
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("a", "a-branch")
        r.commit("branch recaptures a")
        git(r.root, "switch", "-q", "main")
        r.publish("a", "a-main", "a source, edited")
        r.commit("main edits a and recaptures")
        git(r.root, "switch", "-q", "topic")
        self.merge("main")
        self.assertEqual(publication_merge.unify(r.root), ["a"])
        self.assertEqual(r.inconsistent(), [])
        self.assertEqual(r.capture("a"), "a-main")

    def test_both_recaptured_from_the_same_sources_keeps_ours(self):
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("a", "a-branch")
        r.commit("branch recaptures a")
        git(r.root, "switch", "-q", "main")
        r.publish("a", "a-main")
        r.commit("main recaptures a")
        self.merge("topic")
        self.assertEqual(publication_merge.unify(r.root), [])
        self.assertEqual(r.inconsistent(), [])
        self.assertEqual(r.capture("a"), "a-main")

    def test_no_publication_change_on_their_side_does_nothing(self):
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.write("README.md", "hello\n")
        r.commit("branch touches no publication")
        git(r.root, "switch", "-q", "main")
        r.publish("b", "b-v2")
        r.commit("main recaptures b")
        self.merge("topic")
        before = (r.root / PUB / "catalog.json").read_text()
        self.assertEqual(publication_merge.unify(r.root), [])
        self.assertEqual((r.root / PUB / "catalog.json").read_text(), before)

    def test_continue_preserves_a_completed_working_capture(self):
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("a", "a-topic")
        r.commit("topic recaptures a")
        git(r.root, "switch", "-q", "main")
        r.publish("b", "b-main")
        r.commit("main recaptures b")
        self.merge("topic")
        publication_merge.unify(r.root)
        r.publish("a", "a-refreshed", "merged a source")
        publication_merge.unify(r.root)
        self.assertEqual(r.capture("a"), "a-refreshed")
        self.assertEqual(r.capture("b"), "b-main")
        self.assertEqual(r.inconsistent(), [])

    def test_continue_rejects_a_working_capture_with_mixed_outputs(self):
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("a", "a-topic")
        r.commit("topic recaptures a")
        git(r.root, "switch", "-q", "main")
        r.publish("b", "b-main")
        r.commit("main recaptures b")
        self.merge("topic")
        publication_merge.unify(r.root)
        r.publish("a", "a-refreshed")
        r.write(f"{PUB}/a/outputs/0.html", "some other capture")
        publication_merge.unify(r.root)
        self.assertEqual(r.capture("a"), "a-topic")
        self.assertEqual(r.inconsistent(), [])

    def test_continue_rejects_a_working_capture_after_its_source_changes(self):
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("a", "a-topic")
        r.commit("topic recaptures a")
        git(r.root, "switch", "-q", "main")
        r.publish("b", "b-main")
        r.commit("main recaptures b")
        self.merge("topic")
        publication_merge.unify(r.root)
        r.publish("a", "a-refreshed")
        r.write("public/docs/assets/agent_transcripts/a/metadata.edn", "a changed again")
        publication_merge.unify(r.root)
        self.assertEqual(r.capture("a"), "a-topic")
        self.assertEqual(r.inconsistent(), [])

    def test_continue_preserves_a_refresh_that_now_has_no_output(self):
        r = self.repo
        git(r.root, "switch", "-q", "-c", "topic")
        r.publish("a", "a-topic")
        r.commit("topic recaptures a")
        git(r.root, "switch", "-q", "main")
        r.publish("b", "b-main")
        r.commit("main recaptures b")
        self.merge("topic")
        publication_merge.unify(r.root)
        catalog = r.catalog()
        source = "assets/agent_transcripts/a/metadata.edn"
        entry = catalog["documents"].pop(source)
        catalog["emptyDocuments"] = {source: {"sources": entry["sources"], "capturedAt": "no output"}}
        r.write(f"{PUB}/catalog.json", json.dumps(catalog))
        publication_merge.unify(r.root)
        self.assertNotIn(source, r.catalog()["documents"])
        self.assertEqual(r.catalog()["emptyDocuments"][source]["capturedAt"], "no output")


if __name__ == "__main__":
    unittest.main()
