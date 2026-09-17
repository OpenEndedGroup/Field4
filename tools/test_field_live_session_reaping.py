"""A live Field in main must survive a smoke run in main on a different port."""
import subprocess
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import unittest
from unittest.mock import patch
import run_field_tests as runner

class LiveSessionReaping(unittest.TestCase):
    def census(self, ps, listeners='', error='', code=0):
        return patch.object(runner.subprocess, 'run', side_effect=[
            subprocess.CompletedProcess([], 0, ps, ''),
            subprocess.CompletedProcess([], code, listeners, error)])
    def test_serving_and_owned_jvms_survive(self):
        # 10 is a serving adopted server; 11 has a live launcher; 12 is its smoke;
        # only 13 has neither owner nor listener. 14 has no readable parent evidence.
        with self.census('10 1\n11 99\n12 11\n13 1\n', '10\n'):
            self.assertEqual(runner._orphaned_nonserving_pids({10,11,12,13,14}), {13})
    def test_no_listeners_is_a_valid_lsof_result(self):
        with self.census('13 1\n', code=1):
            self.assertEqual(runner._orphaned_nonserving_pids({13}), {13})
    def test_incomplete_listener_census_spares_everyone(self):
        with self.census('13 1\n', error='permission denied', code=1):
            self.assertEqual(runner._orphaned_nonserving_pids({13}), set())
        with patch.object(runner.subprocess,'run',side_effect=FileNotFoundError()):
            self.assertEqual(runner._orphaned_nonserving_pids({13}), set())
    def test_sigkill_recheck_spares_a_process_that_started_serving(self):
        with patch.object(runner,'_classify_field_jvms',return_value=([(13,None)],[])):
            with self.census('13 1\n','13\n'):
                self.assertEqual(runner._stale_field_jvm_pids(exclude=set()), [])

if __name__=='__main__': unittest.main()
