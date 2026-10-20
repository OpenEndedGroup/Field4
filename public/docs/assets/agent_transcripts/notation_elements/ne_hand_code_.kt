// Stands in for a hand: sends a stroke, an arrow and a text from the page over the same
// `notation-scene` message the drawing layer sends. Needs the document open in a browser.
val listen = inside.boxes.values.first { it.name == "listen" }
val f = listen.frame
val t = System.currentTimeMillis()
display(
    "hand",
    """
<script type="module">
import { send } from "./afield.mjs";
const base = (id, type, x, y, w, h) => ({ id, type, x, y, width: w, height: h, angle: 0,
    strokeColor: "#1e1e1e", backgroundColor: "transparent", fillStyle: "solid", strokeWidth: 2,
    strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], frameId: null, roundness: null,
    seed: 1, version: 1, versionNonce: 1, isDeleted: false, boundElements: [], link: null, locked: false });
const stroke = { ...base("hand-stroke-$t", "freedraw", 1100, 760, 200, 40),
    points: [[0, 0], [50, 20], [100, 0], [150, 30], [200, 40]], pressures: [], simulatePressure: true };
const arrow = { ...base("hand-arrow-$t", "arrow", 1100, 670, ${f.x + f.w + 4 - 1100}, ${f.y + f.h / 2 - 670}),
    points: [[0, 0], [${f.x + f.w + 4 - 1100}, ${f.y + f.h / 2 - 670}]], startBinding: null, endBinding: null,
    startArrowhead: null, endArrowhead: "arrow" };
const text = { ...base("hand-text-$t", "text", 1100, 830, 120, 25), text: "by hand", originalText: "by hand",
    fontSize: 20, fontFamily: 5, textAlign: "left", verticalAlign: "top", containerId: null, lineHeight: 1.25 };
send("notation-scene", { scene: JSON.stringify({ type: "excalidraw", version: 2, elements: [stroke, arrow, text] }) });
</script>sent"""
        .trimIndent(),
)
