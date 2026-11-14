/*
The M-VAVE FM-1's open-firmware simulator in Field's audio engine. Launch this box to hear
it; stop the box and it leaves the mix.

Change the project slot (0..3) and re-run: the simulator is shared, so the new project
loads while the transport keeps its state. PLAY/STOP is a toggle, hence the check.
*/
val fm1 = FM1Sim.shared

fm1.preset(1)
if (!fm1.isPlaying) fm1.play()
fm1.level(0.8)

fm1.node
