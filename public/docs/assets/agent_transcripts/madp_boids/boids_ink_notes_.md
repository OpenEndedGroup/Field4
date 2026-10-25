# Ink

The boids ride a slowly changing flow field and barely flock at all. `flow(1.0)` steers each one
along `flowAngle(uv, t)` in the compute tab, the page's value noise turned into an angle. No bodies
are drawn, and `fade = 0.01` keeps nearly every stroke, so the window becomes a page of ink;
**reset** starts a new one. Change the noise's scale (2.2) for finer or broader currents.
