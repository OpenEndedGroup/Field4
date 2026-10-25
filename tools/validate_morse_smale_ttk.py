"""TTK half of tools/validate_morse_smale.py: run in a Python environment that has TTK.

Reads a 2D float32 field from an .npy file, computes TTK's persistence diagram on it, and prints
one JSON line: finite pairs as [birth, death] per dimension (0: minimum-saddle, 1: saddle-maximum).

TTK triangulates a 2D image along the other diagonal from field_morse_smale's Freudenthal split,
so the field is flipped vertically first, which maps one triangulation onto the other. Pair values
do not depend on orientation.
"""

import json
import sys

import numpy as np
import topologytoolkit as ttk
import vtk
from vtk.util import numpy_support as ns

field = np.load(sys.argv[1]).astype(np.float32)
flip = "--no-flip" not in sys.argv
if flip:
    field = field[::-1]
h, w = field.shape
image = vtk.vtkImageData()
image.SetDimensions(w, h, 1)
array = ns.numpy_to_vtk(np.ascontiguousarray(field).ravel(), deep=1)
array.SetName("f")
image.GetPointData().AddArray(array)
diagram = ttk.ttkPersistenceDiagram()
diagram.SetInputDataObject(image)
diagram.SetInputArrayToProcess(0, 0, 0, 0, "f")
diagram.Update()
out = diagram.GetOutput()
cells = out.GetCellData()
pair_type = ns.vtk_to_numpy(cells.GetArray("PairType"))
birth = ns.vtk_to_numpy(cells.GetArray("Birth"))
persistence = ns.vtk_to_numpy(cells.GetArray("Persistence"))
finite = ns.vtk_to_numpy(cells.GetArray("IsFinite"))
pairs = {0: [], 1: []}
for t, b, p, f in zip(pair_type, birth, persistence, finite):
    if t < 0 or not f:
        continue
    pairs[0 if t == 0 else 1].append([float(b), float(b + p)])
print(json.dumps(pairs))
