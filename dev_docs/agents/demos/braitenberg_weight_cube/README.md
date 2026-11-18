# Braitenberg learning in the weight cube

Open `metadata.edn` in Field (`./field.sh <path to this folder>/metadata.edn`) and run the three
boxes; they are the three figures of "The path through the weight cube" in
`public/docs/braitenberg_vehicles.md` §4. CPU only; box 2 and 3 take a few seconds each.

| box | what it does |
| --- | --- |
| 1 | one `WeightCubeLearner` on the light reward, its path through (same, cross, drive) with dots at 0/50/250/500/2000 tries |
| 2 | twelve learners from random starts: five reach the lover basin, seven stay where `drive` pins the motors |
| 3 | three learners for each of `SEEK`, `FLEE`, `FREEZE` and `CHARGE`, each reward ending in its own basin |

Method after Aatish Bhatia and Arkarup Banerjee, [Watch a Brain Learn](https://aatishb.com/myriad/how-brains-learn/).
