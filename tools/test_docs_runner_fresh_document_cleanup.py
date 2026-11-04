"""A `fresh-document=true` capture must not leave its document in the runner's workspace.

Left there, every later capture saw it: the cmd+t template palette lists each workspace document as
a "Workspace file", so templates.md's palette figure listed fourteen leftover `docs-templates-*`
documents under the template it was about.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402


def fresh_options() -> "docs_hooks.RunnerOptions":
    return docs_hooks.parse_runner_options(
        runner_name="diffstatic_runner",
        open_line='```diffstatic_runner {data-lang=kotlin capture="ui" screenshot="assets/x.png" '
                  'document="docs-templates" fresh-document=true timeout="5"}',
        page_path=Path(__file__).resolve().parent.parent / "public" / "docs" / "templates.md",
        line_number=1,
        block_index=0,
    )


def make_document(workspace: Path, name: str) -> Path:
    doc = workspace / name
    doc.mkdir(parents=True)
    (doc / "metadata.edn").write_text('{"version" 2 "boxes" []}')
    return doc


class RemoveFreshDocumentTest(unittest.TestCase):
    def test_the_generated_document_is_removed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            options = fresh_options()
            name = docs_hooks.invocation_document(options)
            doc = make_document(ws, name)
            self.assertTrue(docs_hooks.remove_fresh_document(ws, name, settle=0))
            self.assertFalse(doc.exists())

    def test_other_documents_are_left_alone(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            kept = [make_document(ws, "docs-templates"), make_document(ws, "firstDocument")]
            for name in ["docs-templates", "firstDocument", "../escape-0123abcd", None]:
                self.assertFalse(docs_hooks.remove_fresh_document(ws, name, settle=0))
            self.assertTrue(all(d.exists() for d in kept))

    def test_the_capture_child_removes_it_even_when_the_capture_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            name = "docs-templates-0123abcd"
            doc = make_document(ws, name)
            runner = docs_hooks.RunnerState.__new__(docs_hooks.RunnerState)
            runner.repo_root = Path(tmp)
            options = fresh_options()
            command = ["python", "field_backdoor.py", "--document", name, "--command", "status"]
            with mock.patch.dict("os.environ", {docs_hooks.RUNNER_WORKSPACE_ENV: str(ws)}), \
                    mock.patch.object(docs_hooks, "run_with_watchdog", side_effect=RuntimeError("capture died")):
                with self.assertRaises(RuntimeError):
                    runner._run_capture_child(command, options=options, label="t", is_video=False)
            self.assertFalse(doc.exists(), "the fresh document outlived its capture")
            # Stopping Field saves what it still holds: the folder comes back, and the runner's
            # shutdown removes it again.
            make_document(ws, name)
            with mock.patch.dict("os.environ", {docs_hooks.RUNNER_WORKSPACE_ENV: str(ws)}):
                runner._remove_fresh_documents_again()
            self.assertFalse(doc.exists(), "the fresh document came back after Field stopped")

    def test_a_late_save_after_detach_is_removed_too(self) -> None:
        import threading
        with tempfile.TemporaryDirectory() as tmp:
            ws = Path(tmp)
            name = "docs-templates-89abcdef"
            make_document(ws, name)
            # Field's detach flush writes the folder back shortly after the capture process exits.
            late = threading.Timer(0.4, lambda: make_document(ws, name))
            late.start()
            self.assertTrue(docs_hooks.remove_fresh_document(ws, name, settle=1.0))
            late.join()
            self.assertFalse((ws / name).exists())


if __name__ == "__main__":
    unittest.main()
