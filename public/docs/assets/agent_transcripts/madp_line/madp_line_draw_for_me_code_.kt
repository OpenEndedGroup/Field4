// Draws three strokes for you, as a hand would: the page sends them over the same `notation-scene`
// message the drawing layer sends when you lift the pencil, so whichever box holds the pen takes
// them. Needs the document open in a window.
val strokes = """[{"x":480,"y":520,"points":[[0,0],[19,-26],[39,-52],[58,-78],[78,-103],[97,-127],[117,-151],[136,-173],[155,-194],[175,-213],[194,-231],[214,-248],[233,-262],[252,-275],[272,-285],[291,-294],[311,-300],[330,-304],[350,-306],[369,-306],[388,-304],[408,-300],[427,-293],[447,-285],[466,-275],[486,-263],[505,-250],[524,-235],[544,-218],[563,-200],[583,-182],[602,-162],[621,-142],[641,-121],[660,-101],[670,-90]]},{"x":977,"y":348,"points":[[0,0],[-7,16],[-18,30],[-33,43],[-52,53],[-75,61],[-100,66],[-127,69],[-156,68],[-185,66],[-215,61],[-243,53],[-268,44],[-291,32],[-311,20],[-326,6],[-337,-8],[-343,-22],[-344,-37],[-341,-51],[-334,-64],[-323,-77],[-309,-88],[-292,-99],[-273,-107],[-253,-115],[-232,-121],[-211,-125],[-189,-128],[-168,-129],[-147,-129],[-127,-127],[-109,-124],[-91,-119],[-74,-113],[-59,-106],[-45,-97],[-32,-87],[-21,-76],[-13,-64],[-7,-52],[-3,-38],[-3,-25],[-6,-11],[-13,2],[-23,15],[-37,27],[-55,37],[-76,47],[-100,54],[-113,57]]},{"x":500,"y":680,"points":[[0,0],[20,8],[39,17],[59,25],[79,33],[99,41],[118,49],[138,56],[158,62],[177,68],[197,74],[217,79],[237,83],[256,87],[276,90],[296,92],[315,94],[335,95],[355,95],[374,94],[394,93],[414,91],[434,88],[453,85],[473,80],[493,76],[512,70],[532,65],[552,58],[572,52],[591,45],[611,37],[631,30],[650,22],[670,14],[680,10]]}]"""
val stamp = System.currentTimeMillis()
display(
    "hand",
    """
<script type="module">
import { send } from "./afield.mjs";
const elements = $strokes.map((s, i) => ({ id: "hand-$stamp-" + i, type: "freedraw", x: s.x, y: s.y,
    width: 1, height: 1, angle: 0, strokeColor: "#1e1e1e", backgroundColor: "transparent", fillStyle: "solid",
    strokeWidth: 2, strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], frameId: null, roundness: null,
    seed: 1, version: 1, versionNonce: 1, isDeleted: false, boundElements: [], link: null, locked: false,
    points: s.points, pressures: [], simulatePressure: true }));
send("notation-scene", { scene: JSON.stringify({ type: "excalidraw", version: 2, elements }) });
</script>three strokes sent"""
        .trimIndent(),
)
