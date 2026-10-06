# OSCQuery in Field — a loadable demo

Open this directory as a Field document (`./field.sh -d <path-to>/oscquery_demo`). Box 1 publishes
an OSCQuery service on http port 9020 (OSC on 9021); the other boxes use it, so nothing else needs
to run. TouchDesigner, Chataigne or Max on the same network will list it as "Field demo".

| box | action | what it shows |
| --- | --- | --- |
| **1. Publish parameters** | run first | `float`, `bool`, `menu`, `vector` parameters; the values list updates when a remote sets one. |
| **2. Field controls, published** | run | a control group whose slider and checkbox are `/surface/gain` and `/surface/mute`. |
| **3. Read it back** | run | `oscQueryRemote` on 127.0.0.1:9020: lists the namespace and sets two values over OSC. |
| **4. Mirror as sliders** | run | `oscQueryMirror`: a Field slider per remote number, following over LISTEN. |
| **5. Animate /demo/lfo** | launch | a read-only value Field writes; box 4's lfo slider follows it. |
| **6. Discover** | run | DNS-SD browse for `_oscjson._tcp`. |

The page is `public/docs/oscquery.md`.
