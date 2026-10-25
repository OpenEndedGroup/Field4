# Sine garden

No pairs here: `pairRadius` stays 0 and `eachPair` never runs. Each particle is held on a soft
spring to a ring about the centre whose radius drifts with noise, pushed along its own axis by
`oscillate`, and drawn toward its neighbours by `clump`. A sinusoid is just another force.
