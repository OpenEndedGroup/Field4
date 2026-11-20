# A Python cell: run it with Alt+Enter, or select lines and run only those.
import math

samples = [round(math.sin(i / 3), 3) for i in range(12)]
print(samples)


@slider("frequency", min=1, max=10, step=0.1, value=3.0)
def on_frequency(f):
    display("main", f"<b>{f:.1f} Hz</b>")
