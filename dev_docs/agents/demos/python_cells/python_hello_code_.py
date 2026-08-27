# A single-line print appears inline, beside the line that made it.
import platform
import numpy as np

print("python", platform.python_version())

# A multi-line one gets a block of its own, and keeps its columns.
a = np.arange(12).reshape(3, 4)
print(a)
