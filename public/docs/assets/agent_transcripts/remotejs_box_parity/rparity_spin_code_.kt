// Run this, then Stop it: the counter in the head freezes.
window.spins = 0
launch(() => { window.spins++ }, "spinner", false)
print("spinning")
