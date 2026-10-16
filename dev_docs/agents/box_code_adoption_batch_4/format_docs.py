"""Use the previous batch's checked Markdown extraction for this batch's pages."""
import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('batch3_format', HERE.parent / 'box_code_adoption_batch_3/format_docs.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.OUT = module.ROOT / 'build/box-code-revision/adoption-4/format-docs'
module.PAGES = ['osc.md', 'chain_ceres_manifold_contours.md']
if __name__ == '__main__': module.main()
