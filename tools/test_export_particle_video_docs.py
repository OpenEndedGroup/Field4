import gzip
import struct
import tempfile
import unittest
from pathlib import Path
from tools import export_particle_video_docs as export


class ExportTest(unittest.TestCase):
    def fixture(self, path, merge=False):
        records=[]
        for ident in (16,17):
            for kind,flags,frame in ((1,1,0),(1,0,1),(2,64 if merge and ident==16 else 128,1)):
                records.append(export.pvt.RECORD.pack(kind,flags,ident,frame,17 if flags==64 else 0,
                    10.,10.,1.,0.,.7,.4,.2,.9,0.,0.,-1.,0.))
        path.write_bytes(export.pvt.HEADER.pack(b'PVTRACK1',1,64,80,64,48,1,25.,2,len(records),1)+b''.join(records))

    def test_subsetting_preserves_complete_recorded_trajectories(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'source.pvt';target=Path(tmp)/'subset.pvt.gz'
            self.fixture(source)
            self.assertEqual(export.subset(source,target),3)
            with gzip.open(target,'rb') as f:
                header=export.pvt.read_header(f)
                rows=list(export.pvt.read_records(f,header))
            self.assertEqual([r[2] for r in rows],[16,16,16])
            self.assertEqual([r[3] for r in rows],[0,1,1])
            self.assertEqual(rows[-1][0],2)

    def test_failed_export_preserves_the_previous_published_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'source.pvt';target=Path(tmp)/'subset.pvt.gz'
            self.fixture(source,merge=True);target.write_bytes(b'previous published result')
            with self.assertRaises(ValueError):export.subset(source,target)
            self.assertEqual(target.read_bytes(),b'previous published result')
            self.assertFalse(list(Path(tmp).glob('.particle-subset-*')))
