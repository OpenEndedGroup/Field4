"""Checks the Field-to-training binary boundary, including held-out time slices and HDR RGB."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import numpy as np
from tools.lighting.train_shader import load_dataset

class TrainingDatasetTest(unittest.TestCase):
    def fixture(self,folder):
        splits={}
        for name,t in [('train',-1.),('validation',.25)]:
            raw=np.array([[.5,-.75,t,-.2,3.5,.125]],dtype='<f4').tobytes()
            (folder/f'{name}.f32').write_bytes(raw)
            splits[name]={'file':f'{name}.f32','rows':1,'parameters':[t],'sha256':hashlib.sha256(raw).hexdigest()}
        manifest={'schema':1,'rowFormat':'little-endian float32: x,y,t,r,g,b','splits':splits}
        (folder/'dataset.json').write_text(json.dumps(manifest))
        return manifest
    def test_coordinates_and_unclamped_rgb_cross_the_wire(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);self.fixture(folder);_,rows=load_dataset(folder)
            np.testing.assert_allclose(rows['train'][0],[.5,-.75,-1,-.2,3.5,.125])
    def test_validation_cannot_reuse_a_training_time(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);m=self.fixture(folder);m['splits']['validation']['parameters']=[-1.]
            (folder/'dataset.json').write_text(json.dumps(m))
            with self.assertRaisesRegex(ValueError,'held-out'):load_dataset(folder)
    def test_reject_corrupted_payload(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);self.fixture(folder);(folder/'train.f32').write_bytes(bytes(24))
            with self.assertRaisesRegex(ValueError,'checksum/stride'):load_dataset(folder)
    def test_reject_wrong_row_count(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);m=self.fixture(folder);m['splits']['train']['rows']=2
            (folder/'dataset.json').write_text(json.dumps(m))
            with self.assertRaisesRegex(ValueError,'checksum/stride'):load_dataset(folder)

if __name__=='__main__':unittest.main()
