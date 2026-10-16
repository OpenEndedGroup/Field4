"""Freeze completed physics, land exactly, and report actual positioned topology."""
from pathlib import Path
import yaml
path=Path('dev_docs/agents/transcripts/confluence_grid.yaml');original=path.read_text();doc=yaml.safe_load(original);code=doc['operations'][0]['create']['properties']['code']
def replace(old,new):
 global code
 assert old in code,old
 code=code.replace(old,new)
start=code.index('// 1. The layout');end=code.index('val terms =',start)
code=code[:start]+'''// 1. The pose uses three rounds of type/port-ordered WL refinement and BFS layers.
//    Equal labels and hash collisions can leave ties; this is a layout heuristic, not a
//    canonical graph labelling algorithm. The final report compares positioned nodes AND
//    port-labelled edges, so equal coordinate sets alone cannot claim equal drawings.
// 2. On completion, freeze physics and ease from that fixed snapshot to the target.
//    The final landing frame snaps exactly and clears velocity.

// Animation controls. Rerun setup after changing these values or the term/scheduler rows.
val stepEvery = 7
val displayEvery = 2
val displayDelayMs = 34L
val landingFrames = 40
val maxFrames = 2600

'''+code[end:]
replace('/** Three rounds of Weisfeiler-Leman refinement. Structural, so isomorphic nets agree. */','/** Three rounds of structural refinement; equal labels and hash collisions can leave ties. */')
replace('A pose determined by topology alone: BFS depth from ROOT for the layer, and within a layer an','A heuristic pose: BFS depth from ROOT for the layer, and within a layer an')
replace('// Unreached nodes are ordered by WL label, never by insertion order.','// Sort by WL label; unresolved ties retain their current order.')
replace('var pose: Map<String, Vec3> = emptyMap()','var pose: Map<String, Vec3> = emptyMap()\n    var landingFrom: Map<String, Vec3> = emptyMap()')
replace('done = true; pose = canonicalPose(compiled.net); return','''done = true
            pose = canonicalPose(compiled.net)
            landingFrom = pos.mapValues { (_, p) -> vec(p.x, p.y, p.z) }
            return''')
replace('fun physics() {','fun physics() {\n        if (done) return')
a=code.index('    /** Once landed,');b=code.index('    fun originX()',a)
code=code[:a]+'''    /** Ease from the frozen last physics frame, then land exactly on the target. */
    fun land() {
        if (!done || landing >= landingFrames) return
        landing++
        val t = landing.toDouble() / landingFrames
        val eased = t * t * (3.0 - 2.0 * t)
        pose.forEach { (id, target) ->
            val from = landingFrom[id] ?: target
            pos[id] = if (landing == landingFrames) vec(target.x, target.y, target.z) else
                vec(from.x + (target.x - from.x) * eased,
                    from.y + (target.y - from.y) * eased,
                    from.z + (target.z - from.z) * eased)
            vel[id] = vec(0.0, 0.0, 0.0)
        }
    }

    /** Actual local coordinates plus node types and port-labelled edges, without node IDs. */
    fun positionedGraph(): List<String> {
        fun nodeKey(node: Node) = node.type.name + "@" + pos.getValue(node.id).toString()
        val nodes = compiled.net.nodes.map { "node:" + nodeKey(it) }
        val edges = compiled.net.edges.map { edge ->
            val endpoints = listOf(nodeKey(edge.a.node) + ":" + edge.a.index,
                                   nodeKey(edge.b.node) + ":" + edge.b.index).sorted()
            "edge:" + endpoints.joinToString("--")
        }
        return (nodes + edges).sorted()
    }

'''+code[b:]
replace('frame < 2600','frame < maxFrames');replace('it.landing >= 40','it.landing >= landingFrames');replace('frame % 7 == 0','frame % stepEvery == 0');replace('frame % 2 == 0','frame % displayEvery == 0');replace('Thread.sleep(34)','Thread.sleep(displayDelayMs)')
replace('val poses = row.map { c -> c.pose.values.map { v -> "%.3f,%.3f,%.3f".format(v.x, v.y, v.z) }.sorted() }.toSet()',
'''val placed = row.map { it.positionedGraph() }.toSet()
    val landed = row.all { c -> c.done && c.landing == landingFrames &&
        c.pose.all { (id, target) -> c.pos[id] == target && c.vel[id] == vec(0.0, 0.0, 0.0) } }''')
replace('"   identical pose: " + (poses.size == 1)','"   landed exactly: " + landed +\n        "   matching positioned graph: " + (landed && placed.size == 1)')
start=original.index('        code: |\n')+len('        code: |\n');end=original.index('  - execute:',start)
updated=original[:start]+''.join('          '+line+'\n' for line in code.splitlines())+original[end:]
a=yaml.safe_load(updated);d=yaml.safe_load(original);d['operations'][0]['create']['properties']['code']=a['operations'][0]['create']['properties']['code'];assert a==d
path.write_text(updated)
side=Path('dev_docs/agents/demos/confluence_grid/8b9d6031-73d8-4a82-95db-b40acfdd2806_code_.kt')
# Both authored bodies start from identical code apart from whitespace.
assert side.read_text().strip()==yaml.safe_load(original)['operations'][0]['create']['properties']['code'].strip()
side.write_text(code)
print('Transcript and demo now use fixed landing snapshots and actual positioned-graph reports')
