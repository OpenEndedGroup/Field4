"""Failure/ownership boundaries for the optional toolchain, without network or Vulkan."""
import hashlib
import os
from pathlib import Path
import subprocess
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.lighting import build_learned_shader as build


class OptionalShaderBuildTest(unittest.TestCase):
    def test_verified_cache_works_offline_without_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'compiler.zip';archive.write_bytes(b'pinned archive')
            checksum=hashlib.sha256(archive.read_bytes()).hexdigest()
            with patch.object(build.urllib.request,'urlopen',side_effect=AssertionError('Network used')):
                build.fetch('https://invalid.example/compiler',checksum,archive,offline=True)

    def test_corrupt_cache_cannot_pass_offline(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'compiler.zip';archive.write_bytes(b'corrupt')
            with patch.object(build.urllib.request,'urlopen',side_effect=AssertionError('Network used')):
                with self.assertRaisesRegex(RuntimeError,'No valid cached'):
                    build.fetch('https://invalid.example/compiler','0'*64,archive,offline=True)
            self.assertEqual(archive.read_bytes(),b'corrupt')

    def test_archive_cannot_write_outside_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);archive=root/'compiler.zip'
            with zipfile.ZipFile(archive,'w') as z:z.writestr('../escaped','untrusted')
            with self.assertRaisesRegex(RuntimeError,'Unsafe compiler archive path'):
                build.extract(archive,root/'unpack')
            self.assertFalse((root/'escaped').exists())

    def test_failed_second_compile_preserves_both_working_binaries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'test.slang';source.write_text('test')
            output=root/'output';output.mkdir()
            for name in ['learned-scalar.spv','learned-coop.spv','learned-build.json']:(output/name).write_bytes(b'previous')
            def compile_one(command,**kwargs):
                dest=Path(command[command.index('-o')+1])
                if 'coop' in dest.name:raise subprocess.CalledProcessError(1,command)
                dest.write_bytes(b'new scalar')
            with patch.object(build.subprocess,'run',side_effect=compile_one):
                with self.assertRaises(subprocess.CalledProcessError):build.compile_shaders(Path('slangc'),source,output,{})
            self.assertTrue(all(p.read_bytes()==b'previous' for p in output.iterdir()))

    @unittest.skipIf(os.name == 'nt', 'Unix compiler packages use symlinks')
    def test_unix_library_links_stay_inside_package(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);archive=root/'compiler.zip'
            link=zipfile.ZipInfo('lib/libslang.so');link.external_attr=(stat.S_IFLNK|0o777)<<16
            with zipfile.ZipFile(archive,'w') as z:
                z.writestr('lib/libslang.so.1',b'library');z.writestr(link,'libslang.so.1')
            build.extract(archive,root/'good')
            self.assertEqual((root/'good/lib/libslang.so').read_bytes(),b'library')
            with zipfile.ZipFile(archive,'w') as z:z.writestr(link,'../../escaped')
            with self.assertRaisesRegex(RuntimeError,'Unsafe compiler archive symlink'):
                build.extract(archive,root/'bad')

    def test_changed_sdk_revision_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);sdk=root/'optional/sdk';sdk.mkdir(parents=True);(sdk/'.git').touch()
            with patch.object(build,'ROOT',root),patch.object(build.subprocess,'check_output',side_effect=['160000 abc 0\toptional/sdk\n','user-edited-revision\n']),patch.object(build.subprocess,'run') as mutate:
                with self.assertRaisesRegex(RuntimeError,'preserve your edits'):
                    build.ensure_sdk({'path':'optional/sdk','revision':'abc'},False)
                mutate.assert_not_called()


if __name__=='__main__':unittest.main()
