"""Exercise WiVRn runtime discovery, the encoder setting, and the real launcher environment/argv handoff."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from tools.field_wivrn_macos import (configure_encoder, encoder_configured, launch_environment,
                                     runtime_manifest)


class FieldWivrnMacosTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        # Resolved: the launcher reports real paths, and macOS's temp directory
        # sits behind the /var -> /private/var symlink.
        self.home = Path(self.temp.name).resolve()
        self.app = self.home / 'Apps/WiVRn.app'
        (self.app / 'Contents/Frameworks').mkdir(parents=True)
        (self.app / 'Contents/Frameworks/libopenxr_wivrn.dylib').touch()
        self.manifest = self.app / 'Contents/Resources/openxr/1/openxr_wivrn.json'
        self.manifest.parent.mkdir(parents=True)
        self.manifest.write_text(json.dumps({'runtime': {
            'name': 'WiVRn', 'library_path': '../../../Frameworks/libopenxr_wivrn.dylib'}}))

    def test_manifest_with_relative_library_is_found(self):
        self.assertEqual(runtime_manifest(self.app), self.manifest)

    def test_missing_app_or_library_is_rejected(self):
        with self.assertRaises(ValueError):
            runtime_manifest(self.home / 'Nowhere.app')
        (self.app / 'Contents/Frameworks/libopenxr_wivrn.dylib').unlink()
        with self.assertRaises(ValueError):
            runtime_manifest(self.app)

    def test_encoder_setting_keeps_other_settings(self):
        config = self.home / '.config/wivrn/config.json'
        self.assertFalse(encoder_configured(config))
        config.parent.mkdir(parents=True)
        config.write_text(json.dumps({'hid-forwarding': False, 'encoder': {'encoder': 'x264'}}))
        self.assertFalse(encoder_configured(config))
        configure_encoder(config)
        self.assertTrue(encoder_configured(config))
        self.assertEqual(json.loads(config.read_text()),
                         {'hid-forwarding': False, 'encoder': {'encoder': 'videotoolbox', 'codec': 'h265'}})

    def test_java_tool_options_are_extended_not_replaced(self):
        loader = self.home / 'libopenxr_loader.dylib'
        env = launch_environment(self.manifest, loader, {'JAVA_TOOL_OPTIONS': '-Xss4m'})
        self.assertEqual(env['JAVA_TOOL_OPTIONS'], f'-Xss4m -Dorg.lwjgl.openxr.libname={loader}')
        self.assertEqual(launch_environment(self.manifest, loader, {})['JAVA_TOOL_OPTIONS'],
                         f'-Dorg.lwjgl.openxr.libname={loader}')

    def test_exec_preserves_arguments_and_scopes_runtime_driver_and_loader(self):
        repo = self.home / 'Field checkout'
        (repo / 'tools').mkdir(parents=True)
        script = repo / 'tools/field_wivrn_macos.py'
        shutil.copyfile(Path(__file__).with_name('field_wivrn_macos.py'), script)
        loader = repo / '.afield-local/openxr/lib/libopenxr_loader.dylib'
        loader.parent.mkdir(parents=True)
        loader.touch()
        launcher = repo / 'field.sh'
        launcher.write_text('#!' + sys.executable + '\nimport json, os, sys\n'
                            'print(json.dumps({"argv":sys.argv[1:], "runtime":os.environ["XR_RUNTIME_JSON"], '
                            '"driver":os.environ["AFIELD_VULKAN_DRIVER"], '
                            '"options":os.environ["JAVA_TOOL_OPTIONS"], "keep":os.environ["FIELD_TEST_KEEP"]}))\n')
        launcher.chmod(0o755)
        default = self.home / '.config/openxr/1/active_runtime.json'
        default.parent.mkdir(parents=True)
        default.write_text('the system default runtime must stay here')
        command = [sys.executable, str(script), '--wivrn', str(self.app)]
        env = {**os.environ, 'HOME': str(self.home), 'XR_RUNTIME_JSON': '/old/runtime.json',
               'AFIELD_VULKAN_DRIVER': 'kosmickrisp', 'FIELD_TEST_KEEP': 'preserved'}
        env.pop('JAVA_TOOL_OPTIONS', None)
        args = ['--', '/workspace with spaces', '-d', 'VR document']

        plan = json.loads(subprocess.run([*command, '--check', *args], env=env, text=True,
                                         capture_output=True, check=True).stdout)
        self.assertIn('Launch plan only', plan['status'])
        self.assertEqual(len(plan['problems']), 1)  # the encoder is not configured yet
        refused = subprocess.run([*command, *args], env=env, text=True, capture_output=True)
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn('--configure-encoder', refused.stderr)

        subprocess.run([*command, '--configure-encoder'], env=env, text=True, capture_output=True, check=True)
        result = subprocess.run([*command, *args], env=env, text=True, capture_output=True, check=True)
        self.assertEqual(json.loads(result.stdout), {
            'argv': args[1:], 'runtime': str(self.manifest), 'driver': 'moltenvk',
            'options': f'-Dorg.lwjgl.openxr.libname={loader.resolve()}', 'keep': 'preserved'})
        self.assertEqual(default.read_text(), 'the system default runtime must stay here')


if __name__ == '__main__':
    unittest.main()
