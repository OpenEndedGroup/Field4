// Field's driver for MorphoHDL. This is the only JavaScript in the bridge that Field wrote;
// everything it imports is upstream morpho, unmodified (see morpho/PROVENANCE.md).
//
// Two jobs:
//   1. Replace grower.js, whose constructor builds morpho's own 2D LayoutEngine. The expansion
//      *schedule* is worth keeping and the layout is not, so the schedule is reproduced here
//      and MorphoDifferentialTest pins it to identical expansion counts.
//   2. Flatten the compiler's SoA graph into typed arrays that cross into the JVM in a handful
//      of bulk copies rather than ~10^5 individual polyglot member reads.
//
// Indices stay GLOBAL (morpho's own cell/net/pin numbering) all the way into Kotlin. Compaction
// is the JVM side's problem. Keeping one numbering across the boundary means a Kotlin-side bug
// and a JS-side bug can never disagree about what "cell 40" means.

import { parse }         from './morpho/js/parser.js';
import { compileModule } from './morpho/js/compiler.js';
import { MaxHeap }       from './morpho/js/utils.js';

export const CELL_INPUT = 1, CELL_OUTPUT = 2, CELL_LUT = 3, CELL_MORPHO = 4;

/** Parse only — used for editor completion and for listing what a source file defines. */
export function inspect(src) {
    const ast = parse(src);
    return JSON.stringify({
        luts: ast.luts.map(l => ({ name: l.name, arity: l.arity, value: l.value })),
        cells: ast.cells.map(c => ({
            name: c.name,
            inputs: c.inputs,
            outputs: c.outputs.length,
            fallback: c.fallback,
            ssa: c.ssa.length
        }))
    });
}

/**
 * Compile a design and return a growth handle. Nothing is expanded yet: the root is a single
 * unexpanded MORPHO cell, which is what makes the growth watchable a step at a time.
 */
export function create(src, name, sigCsv, optimize, maxFanout) {
    const sig = String(sigCsv).split(',').filter(s => s.length).map(Number);
    const ast = parse(src);
    const { createRoot } = compileModule(ast, name, sig, !!optimize, maxFanout > 0 ? maxFanout : null);
    const g = createRoot();

    const heap = new MaxHeap((a, b) => a.size - b.size);
    const state = { g, heap, optimize: !!optimize, seeded: false, steps: 0, headIdx: 0 };
    return state;
}

function seed(state) {
    if (state.seeded) return;
    const { g, heap } = state;
    for (let i = 0; i < g.cell.count; i++) {
        if (g.isExpandable(i)) heap.push({ cellIdx: i, size: g.getCellEstimateSize(i) });
    }
    state.seeded = true;
}

// grower.js's _expandCell: expand, then chase a chain of single-child expansions so that a
// recursion which unrolls one cell at a time does not cost one frame per level.
function expandOne(state, cellIdx) {
    const g = state.g;
    const initial = g.cell.count;
    let start = initial;
    if (!g.expandCell(cellIdx, state.optimize)) return -1;
    while (g.cell.count - start === 1) {
        const next = start;
        if (!g.isExpandable(next)) break;
        start = g.cell.count;
        if (!g.expandCell(next, state.optimize)) break;
    }
    return initial;
}

/**
 * Expand up to `budget` cells. `method` is 'largest' (morpho's default, biggest-subtree-first,
 * which grows the circuit outline early) or 'bfs' (index order, which grows it locally).
 * Returns the number of expansions actually performed — 0 means fully grown.
 */
export function grow(state, budget, method) {
    const g = state.g;
    let done = 0;

    if (method === 'bfs') {
        while (done < budget && state.headIdx < g.cell.count) {
            const idx = state.headIdx++;
            if (!g.isExpandable(idx)) continue;
            if (expandOne(state, idx) >= 0) { done++; state.steps++; }
        }
        return done;
    }

    seed(state);
    const heap = state.heap;
    while (done < budget && heap.length > 0) {
        const cellIdx = heap.pop().cellIdx;
        if (g.cell.active[cellIdx] !== 1 || !g.isExpandable(cellIdx)) continue;
        const initial = expandOne(state, cellIdx);
        if (initial < 0) continue;
        for (let i = initial; i < g.cell.count; i++) {
            if (g.isExpandable(i)) heap.push({ cellIdx: i, size: g.getCellEstimateSize(i) });
        }
        done++; state.steps++;
    }
    return done;
}

export function isFullyGrown(state) {
    const g = state.g;
    for (let i = 0; i < g.cell.count; i++) if (g.isExpandable(i)) return false;
    return true;
}

/**
 * Snapshot the netlist as flat typed arrays.
 *
 * Copies rather than views: morpho's SoA reallocates its backing buffers as the graph grows, so
 * a view handed to the JVM would silently detach on the next expansion.
 */
export function extract(state) {
    const g = state.g;
    const nCells = g.cell.count, nNets = g.net.count, nPins = g.pin.count;

    const cellMeta = new Int32Array(nCells * 8);
    for (let i = 0; i < nCells; i++) {
        const o = i * 8;
        cellMeta[o    ] = g.cell.active[i];
        cellMeta[o + 1] = g.cell.type[i];
        cellMeta[o + 2] = g.cell.lutValue[i] | 0;
        cellMeta[o + 3] = g.cell.lutArity[i];
        cellMeta[o + 4] = g.cell.parent[i];
        cellMeta[o + 5] = g.cell.netStart[i];
        cellMeta[o + 6] = g.cell.outputCount[i];
        cellMeta[o + 7] = g.cell.pinStart[i];
    }

    const cellPinCount = new Int32Array(nCells);
    for (let i = 0; i < nCells; i++) cellPinCount[i] = g.cell.inputCount[i];

    const pinNet = new Int32Array(nPins);
    for (let i = 0; i < nPins; i++) pinNet[i] = g.pin.net[i];

    const netDriver = new Int32Array(nNets);
    for (let i = 0; i < nNets; i++) netDriver[i] = g.net.driverCell[i];

    // Global IO. inputCells/outputCells are cell indices; a design's port order is their order.
    const inputCells  = Int32Array.from(g.inputCells  || []);
    const outputCells = Int32Array.from(g.outputCells || []);

    const names = [];
    for (let i = 0; i < nCells; i++) names.push(g.cell.name[i] || '');

    return {
        nCells, nNets, nPins,
        steps: state.steps,
        cellMeta, cellPinCount, pinNet, netDriver,
        inputCells, outputCells,
        names: names.join('\n')
    };
}

/**
 * Signal arrival times, morpho's own updateTimes(). This is the "simulation" its viewer animates:
 * per-cell first/last input arrival, i.e. logic depth, not logic value.
 */
export function timing(state) {
    const g = state.g;
    g.updateTimes();
    const n = g.cell.count;
    const out = new Float32Array(n * 2);
    for (let i = 0; i < n; i++) {
        out[i * 2]     = g.cell.firstTime[i];
        out[i * 2 + 1] = g.cell.lastTime[i];
    }
    return out;
}
