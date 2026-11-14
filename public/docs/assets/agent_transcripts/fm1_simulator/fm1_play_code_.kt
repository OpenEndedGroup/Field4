/*
The M-VAVE FM-1's open-firmware simulator in Field's audio engine. Launch this box to hear
it; stop the box and it leaves the mix.

Change the project slot (0 to 15) and re-run: the simulator is shared, so the new project
loads into the same one. start() starts from the first step whether it was playing or not.
*/
val fm1 = FM1Sim.shared

fm1.preset(1)
fm1.start()
fm1.level(0.8)

fm1.node
