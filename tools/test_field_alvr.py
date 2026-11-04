"""Exercise runtime discovery and the real launcher environment/argv handoff."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from tools.field_alvr import steamvr_manifest


class FieldAlvrTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.steam = self.home / 'Steam library/SteamVR'
        self.steam.mkdir(parents=True)
        (self.steam / 'vrclient.so').touch()
        self.manifest = self.steam / 'steamxr_linux64.json'
        self.manifest.write_text(json.dumps({'runtime': {
            'VALVE_runtime_is_steamvr': True, 'library_path': 'vrclient.so'}}))

    def test_custom_library_from_openvr_registry(self):
        registry = self.home / '.config/openvr/openvrpaths.vrpath'
        registry.parent.mkdir(parents=True)
        registry.write_text(json.dumps({'runtime': [str(self.home / 'removed'), str(self.steam)]}))
        self.assertEqual(steamvr_manifest(self.home), self.manifest)

    def test_missing_library_or_other_runtime_is_rejected(self):
        (self.steam / 'vrclient.so').unlink()
        with self.assertRaises(ValueError):
            steamvr_manifest(self.home, self.steam)
        self.manifest.write_text('{"runtime":{"name":"WiVRn"}}')
        with self.assertRaises(ValueError):
            steamvr_manifest(self.home, self.steam)

    def test_exec_preserves_arguments_and_scopes_runtime_and_input(self):
        repo = self.home / 'Field checkout'
        (repo / 'tools').mkdir(parents=True)
        script = repo / 'tools/field_alvr.py'
        shutil.copyfile(Path(__file__).with_name('field_alvr.py'), script)
        launcher = repo / 'field.sh'
        launcher.write_text('#!' + sys.executable + '\nimport json, os, sys\n'
                            'print(json.dumps({"argv":sys.argv[1:], "runtime":os.environ["XR_RUNTIME_JSON"], '
                            '"input":os.environ["FIELD_VR_INPUT"], "keep":os.environ["FIELD_TEST_KEEP"]}))\n')
        launcher.chmod(0o755)
        default = self.home / '.config/openxr/1/active_runtime.json'
        default.parent.mkdir(parents=True)
        default.write_text('WiVRn default must stay here')
        command = [sys.executable, str(script), '--steamvr', str(self.steam)]
        env = {**os.environ, 'HOME': str(self.home), 'XR_RUNTIME_JSON': '/old/wivrn.json',
               'FIELD_VR_INPUT': 'wivrn', 'FIELD_TEST_KEEP': 'preserved'}
        args = ['--', '/workspace with spaces', '-d', 'VR document']
        plan = subprocess.run([*command, '--check', *args], env=env, text=True,
                              capture_output=True, check=True)
        self.assertIn('Launch plan only', json.loads(plan.stdout)['status'])
        result = subprocess.run([*command, *args], env=env, text=True,
                                capture_output=True, check=True)
        self.assertEqual(json.loads(result.stdout), {
            'argv': args[1:], 'runtime': str(self.manifest), 'input': 'desktop', 'keep': 'preserved'})
        self.assertEqual(default.read_text(), 'WiVRn default must stay here')


if __name__ == '__main__':
    unittest.main()
