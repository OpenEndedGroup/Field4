"""Keep hardware reference images out of automatic docs recapture on unsupported hosts."""
import os
from pathlib import Path
import sys
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'public'))
import docs_hooks

PAGES=('direct_lighting','neural_reconstruction','ray_reconstruction','optical_flow',
       'learned_shader','reconstruction_plumbing','path_tracing','ray_tracing_examples',
       'path_traced_reconstruction','computed_ray_geometry','gpu_shader_learning')


class HardwareDocsGatingTest(unittest.TestCase):
    def state(self,docs):
        return SimpleNamespace(docs_dir=docs,repo_root=ROOT,runner_mode='always',runner_media='all',
            config={'site_dir':str(ROOT/'build/test-doc-site'),'use_directory_urls':True},
            assets_reused=0,missing_assets=0,continue_on_error=False,
            ensure_screenshot=Mock(),ensure_video=Mock(),ensure_audio=Mock(),ensure_ui_drive=Mock(),
            capture_with_environment=Mock(side_effect=AssertionError('GPU capture attempted')))

    def test_mac_always_mode_retains_all_hardware_pages_even_with_profile_requested(self):
        state=self.state(ROOT/'public/docs')
        with patch.object(docs_hooks.sys,'platform','darwin'),patch.dict(os.environ,{'FIELD_DOCS_CAPTURE_PROFILES':'linux-nvidia'}),patch.object(docs_hooks,'register_generated_file'):
            for name in PAGES:
                page=state.docs_dir/(name+'.md')
                with self.subTest(page=name):
                    docs_hooks.process_markdown(markdown=page.read_text(),page_path=page,state=state,files=[])
        state.capture_with_environment.assert_not_called()
        self.assertEqual(state.assets_reused,10)
        self.assertEqual(state.missing_assets,0)

    def test_linux_always_mode_also_requires_explicit_opt_in(self):
        state=self.state(ROOT/'public/docs')
        with patch.object(docs_hooks.sys,'platform','linux'),patch.dict(os.environ,{'FIELD_DOCS_CAPTURE_PROFILES':''}),patch.object(docs_hooks,'register_generated_file'):
            for name in ('path_tracing','ray_tracing_examples'):
                page=state.docs_dir/(name+'.md')
                docs_hooks.process_markdown(markdown=page.read_text(),page_path=page,state=state,files=[])
        state.capture_with_environment.assert_not_called()
        self.assertEqual(state.assets_reused,10)

    def test_profile_is_explicit_and_unknown_names_fail(self):
        with patch.object(docs_hooks.sys,'platform','linux'),patch.dict(os.environ,{'FIELD_DOCS_CAPTURE_PROFILES':'linux-nvidia'}):
            self.assertTrue(docs_hooks.capture_profile_enabled('linux-nvidia'))
            self.assertTrue(docs_hooks.capture_profile_enabled(None))
            with self.assertRaisesRegex(RuntimeError,'Unknown docs capture profile'):
                docs_hooks.capture_profile_enabled('misspelled-profile')

    def test_missing_protected_asset_fails_without_attempting_replacement(self):
        with tempfile.TemporaryDirectory() as temporary:
            docs=Path(temporary);page=docs/'test.md';state=self.state(docs)
            markdown='```diffstatic_runner {capture-profile="linux-nvidia" screenshot="missing.png"}\nprintln("gpu")\n```\n'
            with patch.object(docs_hooks.sys,'platform','darwin'),self.assertRaisesRegex(RuntimeError,'Missing retained'):
                docs_hooks.process_markdown(markdown=markdown,page_path=page,state=state,files=[])
            state.capture_with_environment.assert_not_called()
            self.assertFalse((docs/'missing.png').exists())


if __name__=='__main__':unittest.main()
