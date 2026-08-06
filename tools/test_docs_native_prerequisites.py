import os
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_zensical_docs import (  # noqa: E402
    ceres_library_path,
    check_native_prerequisites,
)

FENCE = (
    '```diffstatic_runner {data-lang=kotlin screenshot="assets/p/kkc.png" '
    'wait="6.0" capture="box" requires="ceres"}\n:::static\nval x = 1\n```\n'
)


class FakeDocs:
    """A throwaway public/ tree with one page, so the scan has something to find."""

    def __init__(self, stack, pages: dict[str, str]):
        root = Path(stack.enter_context(TemporaryDirectory()))
        (root / "public" / "docs").mkdir(parents=True)
        for name, body in pages.items():
            (root / "public" / "docs" / name).write_text(body, encoding="utf-8")
        self.repo_root = root
        self.public_dir = root / "public"


class CeresLibraryPathTests(unittest.TestCase):
    def test_the_environment_override_wins(self):
        os.environ["AFIELD_CERES_LIBRARY"] = "/somewhere/libken_ceres_jvm.dylib"
        self.addCleanup(os.environ.pop, "AFIELD_CERES_LIBRARY", None)
        self.assertEqual(
            ceres_library_path(Path("/repo")), Path("/somewhere/libken_ceres_jvm.dylib")
        )

    def test_the_default_is_where_buildCeresRedistributable_writes(self):
        os.environ.pop("AFIELD_CERES_LIBRARY", None)
        resolved = ceres_library_path(Path("/repo"))
        self.assertEqual(resolved.parent, Path("/repo/build/ken-ceres-redistributable"))
        self.assertTrue(resolved.name.startswith("ken_ceres_jvm") or resolved.name.startswith("libken_ceres_jvm"))


class NativePrerequisiteTests(unittest.TestCase):
    def setUp(self):
        import contextlib

        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        os.environ["AFIELD_CERES_LIBRARY"] = "/definitely/not/here/libken_ceres_jvm.dylib"
        self.addCleanup(os.environ.pop, "AFIELD_CERES_LIBRARY", None)

    def docs(self, pages):
        return FakeDocs(self.stack, pages)

    def check(self, docs, *, runner_mode, page_filters=()):
        check_native_prerequisites(
            public_dir=docs.public_dir,
            repo_root=docs.repo_root,
            page_filters=page_filters,
            runner_mode=runner_mode,
        )

    def test_a_full_recapture_fails_immediately_and_names_the_build_command(self):
        docs = self.docs({"polytope_examples.md": FENCE})
        with self.assertRaises(RuntimeError) as caught:
            self.check(docs, runner_mode="always")
        message = str(caught.exception)
        self.assertIn("./gradlew buildCeresRedistributable", message)
        self.assertIn("polytope_examples.md", message)

    def test_incremental_only_warns_because_the_committed_asset_may_cover_it(self):
        docs = self.docs({"polytope_examples.md": FENCE})
        self.check(docs, runner_mode="incremental")

    def test_runner_mode_never_does_not_scan_at_all(self):
        docs = self.docs({"polytope_examples.md": FENCE})
        self.check(docs, runner_mode="never")

    def test_a_page_filter_that_excludes_the_page_excludes_its_prerequisite(self):
        docs = self.docs({"polytope_examples.md": FENCE, "rectsl_examples.md": "no fences\n"})
        self.check(docs, runner_mode="always", page_filters=("rectsl_examples",))

    def test_a_present_artifact_passes(self):
        docs = self.docs({"polytope_examples.md": FENCE})
        artifact = docs.repo_root / "build" / "ken-ceres-redistributable" / "lib.dylib"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"")
        os.environ["AFIELD_CERES_LIBRARY"] = str(artifact)
        self.check(docs, runner_mode="always")

    def test_an_unknown_key_is_an_authoring_error_not_a_silent_pass(self):
        docs = self.docs({"p.md": FENCE.replace('requires="ceres"', 'requires="oiled-bearings"')})
        with self.assertRaises(RuntimeError) as caught:
            self.check(docs, runner_mode="always")
        self.assertIn("oiled-bearings", str(caught.exception))

    def test_a_fence_without_requires_declares_nothing(self):
        docs = self.docs({"p.md": FENCE.replace(' requires="ceres"', "")})
        self.check(docs, runner_mode="always")

    def test_the_word_requires_in_prose_is_not_a_declaration(self):
        docs = self.docs({"p.md": 'This capture requires="ceres" in spirit only.\n'})
        self.check(docs, runner_mode="always")


if __name__ == "__main__":
    unittest.main()
