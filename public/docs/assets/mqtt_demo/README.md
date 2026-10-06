# MQTT in Field — a loadable demo

Open this directory as a Field document (`./field.sh -d <path-to>/mqtt_demo`). Everything goes
through Field's built-in broker; no network or install is needed.

| box | action | what it shows |
| --- | --- | --- |
| **1. Pretend sensors** | launch first | Publishes `sensors/kitchen/temp`, `sensors/hall/temp` (numbers) and `sensors/garden/env` (JSON) ten times a second. |
| **2. Listen** | run | `subscribe("sensors/+/temp")`: the latest temperature from either room. |
| **3. Plot** | launch | `track("sensors/#")` and `showSignals` over `series(...)`, including a field read out of the JSON. |
| **4. Retained** | run | A retained scene message, received by a subscription made after it was published. |

Stop box 1 to stop the feed. The page is `public/docs/mqtt.md`.
