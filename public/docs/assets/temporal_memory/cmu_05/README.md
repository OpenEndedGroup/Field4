# CMU motion capture, subject 05 (modern dance)

Six takes from the CMU Graphics Lab Motion Capture Database, subject 05, in the BVH conversion by
Bruce Hahne (cgspeed, 2010 release), as mirrored at https://github.com/una-dinosauria/cmu-mocap.
Gzipped, otherwise unchanged. 31 joints, 120 frames per second.

| take | CMU description |
|---|---|
| 05_02 | dance - expressive arms, pirouette |
| 05_07 | dance - small jetes, attitude/arabesque, shifted-axis pirouette, turn |
| 05_09 | dance - glissade devant, glissade derriere, attitude/arabesque |
| 05_12 | dance - arms held high, pointe tendue a terre, upper body rotation |
| 05_14 | dance - retire derriere, attitude/arabesque |
| 05_18 | dance - attitude/arabesque, jete en tourant, bending back |

The data used here was obtained from mocap.cs.cmu.edu. The database was created with funding
from NSF EIA-0196217. CMU places no restrictions on its use; it asks that the data not be resold
on its own, and that it be credited as above.

Read with `afield.io.mocap.Bvh.read("public/docs/assets/temporal_memory/cmu_05/05_02.bvh.gz")`.
Used by public/docs/temporal_memory.md and the `temporal_memory` starting point.
