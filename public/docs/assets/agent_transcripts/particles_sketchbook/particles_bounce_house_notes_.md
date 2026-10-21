# Bounce house

Gravity is a world setting, added to every particle before anything else runs. Collisions are
pairwise, in `eachPair`; the walls are each particle's own business, in `update()`. Drag to pour
in more balls. Mass grows with the radius, so the big ones push the small ones aside.
