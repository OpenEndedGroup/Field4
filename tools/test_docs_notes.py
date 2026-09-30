"""Tests for tools/docs_notes.py: anchoring notes to Markdown, tracking them through commits with
`git blame`, the queue's states, run journals, per-page partitioning, and the harness end to end
with a scripted stand-in for the agent.

Every git test builds its own throwaway repository, so none of them reads or writes the human's
annotation store or this checkout's history.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import textwrap
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from tools import docs_notes as dn

REPO_ROOT = Path(__file__).resolve().parent.parent
PAGE = "https://openendedgroup.com/field4/guide/"

GUIDE = textwrap.dedent("""\
    # A guide

    Intro paragraph that says **hello** to the [reader](other.md).

    ## Setup

    Install the thing, then continue to the next step.

    ```kotlin
    val x = 1 // continue
    ```

    ## Usage

    Run `field.sh` and continue reading about *everything* else.
    """)


class TempRepo:
    def __init__(self) -> None:
        self._dir = TemporaryDirectory()
        self.root = Path(self._dir.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "Test")
        self.git("config", "commit.gpgsign", "false")

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout

    def write(self, rel: str, text: str) -> None:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def commit(self, message: str = "c") -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD").strip()

    def close(self) -> None:
        self._dir.cleanup()


def note(exact: str, **anchor: str) -> dict:
    return dn.normalize_note({"id": "n-" + re.sub(r"\W+", "-", exact)[:20], "pageUrl": PAGE, "note": "fix this",
                              "anchor": {"exact": exact, **anchor}, "createdAtEpochMs": 1})


class ProjectionTest(unittest.TestCase):
    def test_rendered_text_is_found_through_markup(self) -> None:
        match = dn.locate_quote(dn.project_markdown(GUIDE), "says hello to the reader")
        self.assertIsNotNone(match)
        self.assertEqual((match.start_line, match.end_line), (3, 3))
        self.assertEqual(match.method, "exact")

    def test_inline_code_and_emphasis_are_transparent(self) -> None:
        match = dn.locate_quote(dn.project_markdown(GUIDE), "Run field.sh and continue reading about everything else.")
        self.assertEqual(match.start_line, 15)

    def test_context_picks_between_repeated_words(self) -> None:
        projection = dn.project_markdown(GUIDE)
        first = dn.locate_quote(projection, "continue", prefix="Install the thing, then ", suffix=" to the next step")
        last = dn.locate_quote(projection, "continue", prefix="Run field.sh and ", suffix=" reading about")
        code = dn.locate_quote(projection, "continue", prefix="val x = 1 // ", suffix="")
        self.assertEqual(first.start_line, 7)
        self.assertEqual(last.start_line, 15)
        self.assertEqual(code.start_line, 10)

    def test_heading_breaks_a_tie_when_there_is_no_context(self) -> None:
        match = dn.locate_quote(dn.project_markdown(GUIDE), "continue", heading="Usage")
        self.assertEqual(match.start_line, 15)

    def test_a_reworded_sentence_is_found_fuzzily(self) -> None:
        match = dn.locate_quote(dn.project_markdown(GUIDE), "Install the thing and then continue on to the next step.")
        self.assertIsNotNone(match)
        self.assertEqual(match.method, "fuzzy")
        self.assertEqual(match.start_line, 7)

    def test_unrelated_text_is_not_found(self) -> None:
        self.assertIsNone(dn.locate_quote(dn.project_markdown(GUIDE), "quantum chromodynamics of teapots"))

    def test_kdoc_comment_markers_are_stripped(self) -> None:
        kotlin = "/**\n * Draws a line between\n * two points, `a` and `b`.\n */\nfun line() {}\n"
        match = dn.locate_quote(dn.project_markdown(kotlin, kotlin=True), "Draws a line between two points, a and b.")
        self.assertEqual((match.start_line, match.end_line), (2, 3))


class PageResolutionTest(unittest.TestCase):
    def test_url_shapes(self) -> None:
        self.assertEqual(dn.page_relative_candidates("https://openendedgroup.com/field4/index.html")[0], "index.md")
        self.assertEqual(dn.page_relative_candidates("https://openendedgroup.com/field4/")[0], "index.md")
        self.assertEqual(dn.page_relative_candidates("https://openendedgroup.com/field4/a/b/")[0], "a/b.md")
        self.assertEqual(dn.page_relative_candidates("https://openendedgroup.com/field4/a/b/index.html")[0], "a/b.md")
        self.assertEqual(dn.page_relative_candidates("https://example.com/x"), [])

    def test_page_url_for_round_trips(self) -> None:
        git = dn.Git(REPO_ROOT)
        self.assertEqual(dn.page_url_for("public/docs/index.md", git), "https://openendedgroup.com/field4/index.html")
        self.assertEqual(dn.page_url_for("guide.md", git), PAGE)

    def test_generated_pages_are_recognised(self) -> None:
        repo = TempRepo()
        try:
            repo.write("public/docs/reference/x.md", "<!-- DO NOT EDIT — generated by afield.docs.Gen from KDoc in src. -->\n"
                                                     "<!-- Regenerate: ./gradlew gen -->\n# X\n")
            repo.write("src/jvmMain/kotlin/afield/Foo.kt", "package afield\n")
            git = dn.Git(repo.root)
            page = dn.resolve_page_source("https://openendedgroup.com/field4/reference/x/", git)
            self.assertEqual(page.kind, "generated")
            self.assertEqual(page.generated["regenerate"], "./gradlew gen")
            source_page = dn.resolve_page_source("https://openendedgroup.com/field4/reference/source/jvmMain/afield/Foo/", git)
            self.assertEqual(source_page.path, "src/jvmMain/kotlin/afield/Foo.kt")
        finally:
            repo.close()


class ForwardTrackingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = TempRepo()
        self.repo.write("public/docs/guide.md", GUIDE)
        self.first = self.repo.commit("first")
        self.git = dn.Git(self.repo.root)

    def tearDown(self) -> None:
        self.repo.close()

    def fresh(self) -> dn.Git:
        return dn.Git(self.repo.root)  # caches are per-instance; a new commit needs a new one

    def test_lines_follow_edits_elsewhere_in_the_file(self) -> None:
        item = note("Install the thing, then continue to the next step.")
        item["source"] = dn.sync_note(item, self.git)
        self.assertEqual(item["source"]["status"], "anchored")
        self.assertEqual(item["source"]["startLine"], 7)
        self.assertTrue(item["source"]["origins"])

        # Three lines inserted above, a paragraph below rewritten: the anchored line is untouched.
        edited = GUIDE.replace("## Setup\n", "## Setup\n\nA new first paragraph.\nAnd another line.\n")
        edited = edited.replace("Run `field.sh`", "Launch `field.sh`")
        self.repo.write("public/docs/guide.md", edited)
        self.repo.commit("edit around")
        item["source"] = dn.sync_note(item, self.fresh())
        self.assertEqual(item["source"]["status"], "moved")
        self.assertEqual(item["source"]["startLine"], 10)
        self.assertIn("continue to the next step", item["source"]["excerpt"])

    def test_the_same_sentence_elsewhere_does_not_capture_the_note(self) -> None:
        item = note("Install the thing, then continue to the next step.")
        item["source"] = dn.sync_note(item, self.git)
        # An identical sentence appears earlier. Text search would pick it; blame identity does not.
        edited = GUIDE.replace("# A guide\n", "# A guide\n\nInstall the thing, then continue to the next step.\n")
        self.repo.write("public/docs/guide.md", edited)
        self.repo.commit("duplicate")
        item["source"] = dn.sync_note(item, self.fresh())
        self.assertEqual(item["source"]["startLine"], 9)

    def test_uncommitted_edits_are_tracked_in_the_working_tree(self) -> None:
        item = note("Install the thing, then continue to the next step.")
        item["source"] = dn.sync_note(item, self.git)
        self.repo.write("public/docs/guide.md", "Preamble.\n\n" + GUIDE)
        item["source"] = dn.sync_note(item, self.fresh())
        self.assertEqual(item["source"]["startLine"], 9)

    def test_an_edited_line_is_reanchored_by_its_quote(self) -> None:
        item = note("Install the thing, then continue to the next step.")
        item["source"] = dn.sync_note(item, self.git)
        self.repo.write("public/docs/guide.md", GUIDE.replace("then continue to the next step.",
                                                              "then continue to the following step."))
        self.repo.commit("reword")
        item["source"] = dn.sync_note(item, self.fresh())
        # The only anchored line changed, so blame loses it; the quote still reads, nearly.
        self.assertEqual(item["source"]["status"], "reanchored")
        self.assertEqual(item["source"]["startLine"], 7)
        self.assertIn("continue to the next step", item["source"]["previousExcerpt"])

    def test_a_rewritten_line_orphans_the_note_and_remembers_where_it_was(self) -> None:
        item = note("Install the thing, then continue to the next step.")
        item["source"] = dn.sync_note(item, self.git)
        self.repo.write("public/docs/guide.md", GUIDE.replace("Install the thing, then continue to the next step.",
                                                              "Nothing about installation remains here."))
        self.repo.commit("rewrite")
        item["source"] = dn.sync_note(item, self.fresh())
        self.assertEqual(item["source"]["status"], "orphaned")
        self.assertEqual(item["source"]["lastKnown"]["startLine"], 7)

    def test_a_note_is_matched_against_the_commit_it_was_read_at(self) -> None:
        # The human read the page at `first`; the sentence has since been reworded and moved.
        item = note("Install the thing, then continue to the next step.", sourceCommit=self.first)
        moved = GUIDE.replace("Install the thing, then continue to the next step.\n", "")
        moved = moved.replace("## Usage\n", "## Usage\n\nInstall the thing, then continue to the next step.\n")
        self.repo.write("public/docs/guide.md", "Preamble line.\n" + moved)
        self.repo.commit("move")
        source = dn.sync_note(item, self.fresh())
        # The moved copy is a new line (a new origin); the original is gone. Matching at
        # `first` finds line 7 there, and blame at HEAD reports it did not survive — unless git
        # detected the move (-M), in which case it is found at its new position.
        self.assertIn(source["status"], ("orphaned", "anchored", "edited"))
        self.assertEqual(source["firstAnchoredAt"], self.first)
        if source["status"] != "orphaned":
            self.assertIn("continue to the next step", source["excerpt"])

    def test_a_selection_in_the_site_header_is_not_pinned_on_the_body(self) -> None:
        # Found by the first real agent run: a note on the header's "Field" landed on the first
        # "Field" in the page, because the old panel measured context in document.body.innerText.
        legacy = note("guide", prefix="Skip to content\nOpenEndedGroup | A ", suffix="\nIntro")
        source = dn.sync_note(legacy, self.git)
        self.assertEqual(source["status"], "unanchored")
        self.assertIn("header", source["reason"])
        flagged = note("Intro paragraph that says", region="chrome")
        self.assertEqual(dn.sync_note(flagged, self.git)["status"], "unanchored")

    def test_a_short_quote_whose_context_disagrees_is_refused(self) -> None:
        item = note("continue", prefix="something the source never says ", suffix=" nor this either")
        source = dn.sync_note(item, self.git)
        self.assertEqual(source["status"], "unanchored")
        self.assertIn("short quote", source["reason"])

    def test_a_renamed_page_is_followed(self) -> None:
        item = note("Install the thing, then continue to the next step.")
        item["source"] = dn.sync_note(item, self.git)
        self.repo.git("mv", "public/docs/guide.md", "public/docs/handbook.md")
        self.repo.commit("rename")
        item["source"] = dn.sync_note(item, self.fresh())
        self.assertEqual(item["source"]["path"], "public/docs/handbook.md")
        self.assertEqual(item["source"]["startLine"], 7)

    def test_history_records_where_the_note_has_been(self) -> None:
        item = note("Install the thing, then continue to the next step.")
        item["source"] = dn.sync_note(item, self.git)
        self.repo.write("public/docs/guide.md", "x\n" + GUIDE)
        self.repo.commit("shift")
        item["source"] = dn.sync_note(item, self.fresh())
        self.assertEqual(item["source"]["history"][-1]["startLine"], 7)


class QueueStateTest(unittest.TestCase):
    def test_states(self) -> None:
        item = dn.normalize_note({"id": "a", "pageUrl": PAGE, "note": "x"})
        self.assertEqual(dn.note_state(item), "open")
        dn.add_entry(item, "question", "which?", "agent:claude")
        self.assertEqual(dn.note_state(item), "question")
        self.assertFalse(dn.in_queue(item))
        dn.add_entry(item, "reply", "this one", "human")
        self.assertEqual(dn.note_state(item), "open")
        dn.apply_op([item], {"op": "resolve", "id": "a", "text": "done", "memory": "keep in mind"})
        self.assertEqual(dn.note_state(item), "resolved")
        self.assertEqual([e["kind"] for e in item["thread"]][-2:], ["resolution", "memory"])
        dn.apply_op([item], {"op": "reopen", "id": "a", "text": ""})
        self.assertTrue(dn.in_queue(item))
        self.assertEqual(dn.note_state(dn.normalize_note({"kind": "memory", "pageUrl": PAGE, "note": "m"})), "memory")

    def test_tag_filter(self) -> None:
        item = dn.normalize_note({"id": "a", "pageUrl": PAGE, "note": "x", "tags": ["voice"]})
        self.assertTrue(dn.in_queue(item, ["@voice"]))
        self.assertFalse(dn.in_queue(item, ["accuracy"]))


class StoreAndJournalTest(unittest.TestCase):
    def setUp(self) -> None:
        self._dir = TemporaryDirectory()
        self.path = Path(self._dir.name) / "store.json"
        self.path.write_text(json.dumps({"version": 1, "annotations": [
            {"id": "aaaa1111", "pageUrl": PAGE, "note": "one", "futureField": 7},
            {"id": "bbbb2222", "pageUrl": PAGE, "note": "two"}]}))
        self.store = dn.Store(self.path)

    def tearDown(self) -> None:
        self._dir.cleanup()

    def test_unknown_fields_survive_a_write(self) -> None:
        self.store.mutate(lambda notes: dn.apply_op(notes, {"op": "comment", "id": "aaaa", "text": "hi"}))
        saved = json.loads(self.path.read_text())["annotations"][0]
        self.assertEqual(saved["futureField"], 7)
        self.assertEqual(saved["thread"][0]["text"], "hi")

    def test_journal_mode_writes_the_journal_not_the_store(self) -> None:
        journal = Path(self._dir.name) / "j.jsonl"
        env = {dn.JOURNAL_ENV: str(journal), dn.PARTITION_ENV: "aaaa1111", dn.AUTHOR_ENV: "agent:test"}
        with mock.patch.dict(os.environ, env):
            dn.record(self.store, {"op": "resolve", "id": "aaaa", "text": "fixed"})
            with self.assertRaises(SystemExit):
                dn.record(self.store, {"op": "resolve", "id": "bbbb2222", "text": "not mine"})
        ops = dn.read_journal(journal)
        self.assertEqual(len(ops), 1)
        self.assertEqual(ops[0]["id"], "aaaa1111")  # the prefix was expanded
        self.assertEqual(ops[0]["author"], "agent:test")
        self.assertNotIn("resolvedAtEpochMs", json.loads(self.path.read_text())["annotations"][0])

    def test_store_path_resolution(self) -> None:
        home, cwd = Path("/h"), Path("/w")
        self.assertEqual(dn.resolve_store_path(environ={}, home=home, cwd=cwd), Path("/h/.afield/docs-annotations.json"))
        self.assertEqual(dn.resolve_store_path(environ={dn.STORE_ENV: "x/s.json"}, home=home, cwd=cwd), Path("/w/x/s.json"))
        self.assertEqual(dn.resolve_store_path("/abs.json", environ={dn.STORE_ENV: "x"}, home=home, cwd=cwd), Path("/abs.json"))


class PartitionTest(unittest.TestCase):
    def test_pages_that_share_a_file_are_one_partition(self) -> None:
        def n(i: str, page: str, path: str) -> dict:
            return {"id": i, "pageUrl": page, "source": {"page": page, "path": path, "startLine": 1}}
        notes = [n("1", "reference/a.md", "src/A.kt"), n("2", "reference/b.md", "src/A.kt"),
                 n("3", "guide.md", "public/docs/guide.md"), n("4", "guide.md", "public/docs/guide.md"),
                 n("5", "other.md", "public/docs/other.md")]
        partitions = dn.partition_notes(notes)
        self.assertEqual(sorted(len(p.notes) for p in partitions), [1, 2, 2])
        merged = next(p for p in partitions if "src/A.kt" in p.files)
        self.assertEqual(merged.pages, ["reference/a.md", "reference/b.md"])
        self.assertEqual(len({p.slug for p in partitions}), 3)


FAKE_AGENT = textwrap.dedent("""\
    import re, subprocess, sys
    prompt = open(sys.argv[1], encoding="utf-8").read()
    cli = re.search(r"^  (.+?) resolve <note-id>", prompt, re.M).group(1)
    owned = re.findall(r"^  - (\\S+)$", prompt, re.M)
    blocks = re.findall(r"^### note (\\S+)(.*?)(?=^### note |^## Memories)", prompt, re.M | re.S)
    path = owned[0]
    text = open(path, encoding="utf-8").read().replace("continue to the next step", "go on to the next step")
    open(path, "w", encoding="utf-8").write(text)
    for note_id, body in blocks:
        if "hello" in body:
            subprocess.run(cli + f" question {note_id} 'which one did you mean?'", shell=True, check=True)
        else:
            subprocess.run(cli + f" resolve {note_id} 'reworded the step' --memory 'nothing pins this wording'",
                           shell=True, check=True)
    """)


class HarnessTest(unittest.TestCase):
    def test_revise_runs_per_page_applies_journals_and_commits(self) -> None:
        repo = TempRepo()
        try:
            repo.write("public/docs/guide.md", GUIDE)
            repo.write("public/docs/other.md", "# Other\n\nA second page with a sentence.\n")
            repo.write(".gitignore", ".field-backdoor/\n")
            repo.commit("docs")
            store_path = repo.root / "store.json"
            other = "https://openendedgroup.com/field4/other/"
            store_path.write_text(json.dumps({"version": 1, "annotations": [
                {"id": "11111111-a", "pageUrl": PAGE, "note": "reword", "anchor": {"exact": "then continue to the next step"}},
                {"id": "22222222-b", "pageUrl": PAGE, "note": "hmm", "anchor": {"exact": "says hello"}},
                {"id": "33333333-c", "pageUrl": other, "note": "fine?", "anchor": {"exact": "A second page"}}]}))
            agent = repo.root / "agent.py"
            agent.write_text(FAKE_AGENT)
            repo.git("add", "agent.py")
            repo.git("commit", "-q", "-m", "agent")
            code = dn.main(["--store", str(store_path), "--repo", str(repo.root), "revise", "--jobs", "2", "--commit",
                            "--agent-command", f"{sys.executable} {agent} {{prompt_file}}"])
            self.assertEqual(code, 0)
            notes = {n["id"]: n for n in json.loads(store_path.read_text())["annotations"]}
            first = notes["11111111-a"]
            self.assertIsNotNone(first.get("resolvedAtEpochMs"))
            resolution = next(e for e in first["thread"] if e["kind"] == "resolution")
            self.assertEqual(resolution["author"], "agent:custom")
            self.assertTrue(resolution.get("commit"))
            self.assertIn("public/docs/guide.md", resolution["changedFiles"])
            self.assertTrue(any(e["kind"] == "memory" for e in first["thread"]))
            self.assertEqual(dn.note_state(notes["22222222-b"]), "question")
            # The re-sync after committing moved the resolved note onto the new text.
            self.assertIn("go on to the next step", first["source"]["excerpt"])
            log = repo.git("log", "--format=%B", "-n", "5")
            self.assertIn("Docs-Note: 11111111-a", log)
            self.assertIn("docs: revise", log)
            # The other page was its own partition; its agent reworded nothing there but still ran.
            self.assertIn("33333333-c", {n["id"] for n in notes.values()})
        finally:
            repo.close()


class KotlinSchemaTest(unittest.TestCase):
    """Every key this tool writes must exist on the Kotlin side, or the server's next write drops it."""

    SOURCE = REPO_ROOT / "src/jvmMain/kotlin/afield/server/docsAnnotations.kt"

    def properties(self, class_name: str) -> set[str]:
        text = self.SOURCE.read_text(encoding="utf-8")
        body = re.search(rf"data class {class_name}\((.*?)\n\)", text, re.S)
        self.assertIsNotNone(body, f"{class_name} not found in {self.SOURCE}")
        return set(re.findall(r"^\s*val (\w+):", body.group(1), re.M))

    def test_annotation_keys(self) -> None:
        missing = dn.WRITTEN_KEYS - self.properties("DocsAnnotation")
        self.assertFalse(missing, f"docs_notes.py writes {sorted(missing)} but DocsAnnotation does not declare them")

    def test_entry_keys(self) -> None:
        item = dn.normalize_note({"id": "a", "pageUrl": PAGE, "note": "x"})
        entry = dn.add_entry(item, "resolution", "t", "agent:x", runId="r", changedFiles=["f"], commit="c")
        missing = set(entry) - self.properties("DocsAnnotationEntry")
        self.assertFalse(missing, f"thread entries carry {sorted(missing)} that DocsAnnotationEntry lacks")
        self.assertEqual(set(dn.ENTRY_KINDS), set(re.findall(r"`(\w+)`", re.search(
            r"/\*\* `comment`.*?\*/", self.SOURCE.read_text(encoding="utf-8")).group(0))))

    def test_anchor_keys_the_panel_sends(self) -> None:
        panel = (REPO_ROOT / "src/commonMain/resources/docsAnnotations.js").read_text(encoding="utf-8")
        capture = re.search(r"const capture = \(\) => \{(.*?)\n    \};", panel, re.S).group(1)
        sent = set(re.findall(r"(\w+):", capture.split("return {")[-1]))
        missing = sent - self.properties("DocsAnnotationAnchor")
        self.assertFalse(missing, f"the panel sends anchor keys {sorted(missing)} the server would drop")


if __name__ == "__main__":
    unittest.main()
