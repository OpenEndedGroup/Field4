"""Cache identity/integrity checks; inference itself is exercised by the Kotlin CUDA smoke."""
import json
from pathlib import Path
import tempfile
import unittest
import cached_track as cache


class CacheTest(unittest.TestCase):
    def test_native_dimensions_and_all_options_invalidate_the_key(self):
        default=cache.options_for({})
        self.assertEqual(default['width'],0)
        base=cache.key_for(default)
        for k,value in dict(particles=24000,color_limit=1.,frames=30,width=960,
                            iterations=12,seed=2,device='cpu',merge_distance=2.,fps=12.).items():
            self.assertNotEqual(base,cache.key_for(cache.options_for({k:value})),k)
        self.assertEqual(cache.key_for(dict(reversed(list(default.items())))),base)
        self.assertEqual(base,cache.key_for(cache.options_for({'spacing':0,'color_limit':.18})))

    def test_invalid_parameters_do_not_reach_inference(self):
        for raw in ({'bogus':1},{'particles':0},{'width':16},{'confidence':float('nan')},
                    {'color_limit':1.1},{'device':'$(touch bad)'},{'frames':True}):
            with self.assertRaises(ValueError):cache.options_for(raw)

    def test_only_complete_matching_checksum_receipts_are_hits(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);identity={'input_sha256':'a','options':{'particles':42}}
            self.assertFalse(cache.valid_entry(folder,identity))
            tracks=folder/'tracks.pvt';tracks.write_bytes(b'finalized track content')
            meta=folder/'tracks.pvt.run.json';meta.write_text('{}')
            (folder/'manifest.json').write_text(json.dumps(dict(identity=identity,
                tracks_sha256=cache.digest(tracks),metadata_sha256=cache.digest(meta))))
            self.assertTrue(cache.valid_entry(folder,identity))
            self.assertFalse(cache.valid_entry(folder,{'input_sha256':'b'}))
            tracks.write_bytes(b'corrupted')
            self.assertFalse(cache.valid_entry(folder,identity))

    def test_source_digest_includes_the_wrapper_reader_and_dense_engine(self):
        identity=cache.source_identity()
        for name in ('cached_track.py','neural_track.py','pvt.py','engine/main.cpp','engine/dense.hpp','engine/tracker.hpp'):
            self.assertIn(name,identity)
            self.assertEqual(len(identity[name]),64)


if __name__=='__main__':unittest.main()
