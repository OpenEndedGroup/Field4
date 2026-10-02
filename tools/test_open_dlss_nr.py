"""Model container and optional offline cache failures; no GPU or downloaded assets required."""
import importlib.util
import hashlib
from pathlib import Path
import struct
import tempfile
import unittest
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from build_opendlssnr import fetch
spec=importlib.util.spec_from_file_location('extract_nr',Path(__file__).parent/'opendlssnr/extract_weights.py')
extract=importlib.util.module_from_spec(spec);spec.loader.exec_module(extract)

def tensor(name, device=1, dtype=0):
    name=name.encode();data=b'\x00\x3c'
    payload=struct.pack('<QQI',42,2,device)+data+struct.pack('<QQI',dtype,1,1)
    return struct.pack('<Q',len(name))+name+struct.pack('<Q',len(payload))+payload

def blob(items):
    payload=b''.join(items);return struct.pack('<Q',len(payload)+8)+payload

class OpenDlssNrPreparationTest(unittest.TestCase):
    def test_complete_map_and_unmodified_bytes(self):
        items=[tensor(f'block{b}.layer{l}.layer') for b in range(71) for l in range(2)]
        items.extend(tensor(f'block{b}.layer2.layer') for b in range(11))
        result=extract.parse_weights(blob(items))
        self.assertEqual(len(result),153)
        self.assertTrue(all(t[-1]==b'\x00\x3c' for t in result))
    def test_duplicate_rejected(self):
        with self.assertRaisesRegex(ValueError,'duplicate'):
            extract.parse_weights(blob([tensor('block0.layer0.layer')]*2))
    def test_wrong_device_and_dtype_rejected(self):
        for item in (tensor('block0.layer0.layer',device=0),tensor('block0.layer0.layer',dtype=1)):
            with self.assertRaises(ValueError):extract.parse_weights(blob([item]))
    def test_incomplete_map_rejected(self):
        with self.assertRaisesRegex(ValueError,'complete'):
            extract.parse_weights(blob([tensor('block0.layer0.layer')]))
    def test_non_pe_rejected(self):
        with self.assertRaisesRegex(ValueError,'PE DLL'):extract.extract_resource(b'not a DLL')
    def test_offline_cache_valid_missing_and_corrupt(self):
        with tempfile.TemporaryDirectory() as temp:
            cache=Path(temp);content=b'archive fixture'
            item=dict(file='fixture.zip',url='https://example.invalid/fixture',sha256=hashlib.sha256(content).hexdigest())
            with self.assertRaisesRegex(RuntimeError,'Offline cache missing'):fetch(item,cache,True)
            (cache/item['file']).write_bytes(content)
            self.assertEqual(fetch(item,cache,True).read_bytes(),content)
            (cache/item['file']).write_bytes(b'corrupt')
            with self.assertRaisesRegex(RuntimeError,'Corrupt cached archive'):fetch(item,cache,True)

if __name__=='__main__':unittest.main()
