# The same controls a Kotlin box draws, calling back into Python.
state = {"freq": 3.0, "square": False}

def redraw():
    f, sq = state["freq"], state["square"]
    wave = "square" if sq else "sine"
    display("main", f"<b>{wave}</b> at {f:.1f} Hz")

@slider("frequency", min=1, max=10, step=0.1, value=3.0)
def on_freq(v):
    state["freq"] = v
    redraw()

@checkbox("square wave")
def on_square(checked):
    state["square"] = checked
    redraw()

redraw()
