"""Include the optional tracker's dependency-free cache checks in checkPythonTools."""
import importlib.util
from pathlib import Path
import sys


def load_tests(loader, tests, pattern):
    directory=Path(__file__).resolve().parents[1]/'optional/particle-tracker'
    spec=importlib.util.spec_from_file_location('field_particle_cache_tests',directory/'test_cache.py')
    module=importlib.util.module_from_spec(spec)
    sys.path.insert(0,str(directory))
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path.pop(0)
    return loader.loadTestsFromModule(module)
