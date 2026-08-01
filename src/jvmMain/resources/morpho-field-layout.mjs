// Field's driver for morpho's Barnes-Hut layout kernel (graphs_engine/main.wasm).
//
// The kernel is 3D: positions are stride-3, the tree is an octree, and `updateNodes` compensates
// angular momentum with a full 3x3 inertia tensor. Upstream's own force_layout.js nonetheless
// writes `pos[p*3+2] = 0` and zeroes z-velocity on every particle every frame, so the shipped
// demo only ever uses it in the plane. This driver does not do that. Field draws in 3D, so the
// third dimension is simply left free and the same kernel lays the circuit out as a solid.
//
// Why the kernel is driven from JavaScript rather than from Kotlin over polyglot buffer reads:
// main.wasm publishes its buffers as `_get_<name>()` / `_len_<name>__<type>()` offset+length
// pairs, a convention designed to be wrapped in typed-array views. Views make sync a bulk
// `.set()`; the polyglot buffer API would make it ~25k single-element crossings per frame.

import { mulberry32 } from './morpho/js/utils.js';

let instance = null;
let api = null;
let capacity = 0;

const TYPE2CLASS = {
    uint8_t: Uint8Array,
    int: Int32Array,
    uint32_t: Uint32Array,
    uint64_t: BigUint64Array,
    float: Float32Array,
    simd_float_t: Float32Array
};

// morpho's prepareWASM, reproduced because it lives in force_layout.js next to the 2D pinning we
// are dropping. Buffers are re-viewed whenever wasm memory grows out from under them.
function prepareWASM(inst) {
    const prefix = '_len_';
    const exports = inst.exports;
    const main = {};
    const arrays = {};
    for (const key in exports) {
        if (!key.startsWith(prefix)) {
            if (!key.startsWith('_')) main[key] = exports[key];
            continue;
        }
        const [name, type] = key.slice(prefix.length).split('__');
        Object.defineProperty(main, name, {
            enumerable: true,
            get() {
                if (!(name in arrays) || arrays[name].buffer !== exports.memory.buffer) {
                    const ofs = exports['_get_' + name]();
                    const len = exports[key]();
                    if (!ofs) return null;
                    arrays[name] = new (TYPE2CLASS[type])(exports.memory.buffer, ofs, len);
                }
                return arrays[name];
            }
        });
    }
    return main;
}

/**
 * Instantiate the kernel from bytes handed over by the JVM.
 *
 * Synchronous `new WebAssembly.Module` / `new WebAssembly.Instance` rather than the async
 * `instantiate`, so Kotlin gets an answer without a promise to drain. The module imports nothing
 * at all, so the import object is empty.
 */
export function initWasm(bytes, maxPoints) {
    if (instance && maxPoints <= capacity) return capacity;

    const n = bytes.length;
    const u8 = new Uint8Array(n);
    for (let i = 0; i < n; i++) u8[i] = bytes[i] & 0xff;

    const module = new WebAssembly.Module(u8);
    instance = new WebAssembly.Instance(module, {});
    api = prepareWASM(instance);

    capacity = Math.max(maxPoints, 1024);
    api.init(capacity, capacity, capacity * 4);
    api.setLinearCompensation(false);
    return capacity;
}

/**
 * Build an options object for [relax].
 *
 * Exists so the defaults live in exactly one place and Kotlin can hand over a real JS object
 * rather than a host Map, whose members would be its Java methods rather than its keys.
 */
export function options(repulsionForce, wireForce, velocityDecay, theta, leafSize,
                        maxLevel, linkDistance, maxDist, maxSpeed, gravity) {
    return { repulsionForce, wireForce, velocityDecay, theta, leafSize,
             maxLevel, linkDistance, maxDist, maxSpeed, gravity };
}

export function createLayout(seed) {
    return {
        pos: new Float32Array(0),
        vel: new Float32Array(0),
        cellOf: new Int32Array(0),
        particleOf: new Int32Array(0),
        n: 0,
        links: null,
        linkN: 0,
        dirty: true,
        random: mulberry32(seed >>> 0)
    };
}

/**
 * Re-map particles onto the graph's currently active cells, preserving the position of any cell
 * that already had one.
 *
 * Called after every growth step. A cell born this step is seeded at its parent's position with a
 * small jitter, so a subtree unfolds outward from where it came from instead of teleporting in
 * from a random point — which is the difference between watching a circuit grow and watching a
 * cloud of dots re-converge.
 */
export function sync(L, state) {
    const g = state.g;
    const cells = [];
    for (let i = 0; i < g.cell.count; i++) if (g.cell.active[i] === 1) cells.push(i);
    const n = cells.length;

    const pos = new Float32Array(n * 3);
    const vel = new Float32Array(n * 3);
    const particleOf = new Int32Array(g.cell.count).fill(-1);
    const cellOf = Int32Array.from(cells);
    for (let p = 0; p < n; p++) particleOf[cells[p]] = p;

    const R = L.random;
    const jitter = () => (R() - 0.5) * 2.0;

    for (let p = 0; p < n; p++) {
        const c = cells[p];
        const old = c < L.particleOf.length ? L.particleOf[c] : -1;
        if (old >= 0 && old < L.n) {
            for (let k = 0; k < 3; k++) {
                pos[p * 3 + k] = L.pos[old * 3 + k];
                vel[p * 3 + k] = L.vel[old * 3 + k];
            }
            continue;
        }
        // New cell. Prefer its parent's current position, then the parent's previous position,
        // then the origin. Parents precede children in cell order, so the first case is ready.
        const par = g.cell.parent[c];
        let px = 0, py = 0, pz = 0;
        if (par >= 0 && par < particleOf.length && particleOf[par] >= 0 && particleOf[par] < p) {
            const q = particleOf[par];
            px = pos[q * 3]; py = pos[q * 3 + 1]; pz = pos[q * 3 + 2];
        } else if (par >= 0 && par < L.particleOf.length && L.particleOf[par] >= 0) {
            const q = L.particleOf[par];
            px = L.pos[q * 3]; py = L.pos[q * 3 + 1]; pz = L.pos[q * 3 + 2];
        }
        pos[p * 3] = px + jitter();
        pos[p * 3 + 1] = py + jitter();
        pos[p * 3 + 2] = pz + jitter();
    }

    L.pos = pos;
    L.vel = vel;
    L.cellOf = cellOf;
    L.particleOf = particleOf;
    L.n = n;
    L.dirty = true;
    return n;
}

/** Deduplicated undirected links between active cells, in particle indices. */
function buildLinks(L, state) {
    const g = state.g;
    const active = g.cell.active;
    const seen = new Set();
    const flat = [];

    for (let p = 0; p < g.pin.count; p++) {
        const netIdx = g.pin.net[p];
        if (netIdx === -1) continue;
        const src = g.net.driverCell[netIdx];
        const dst = g.pin.cell[p];
        if (src === -1 || dst === -1 || active[src] === 0 || active[dst] === 0) continue;
        const a = L.particleOf[src], b = L.particleOf[dst];
        if (a === -1 || b === -1 || a === b) continue;
        // 2^20 particles per side keeps the key under 2^53, unlike upstream's 16-bit packing.
        const key = a < b ? a * 1048576 + b : b * 1048576 + a;
        if (seen.has(key)) continue;
        seen.add(key);
        flat.push(a, b);
    }

    L.links = Int32Array.from(flat);
    L.linkN = flat.length / 2;
    L.dirty = false;
    return L.linkN;
}

/**
 * Advance the layout by `steps` Barnes-Hut iterations.
 *
 * Same force schedule as upstream's relax() — link force, octree, multibody, charge, centring
 * gravity, integrate — with gravity applied on all three axes instead of two.
 */
export function relax(L, state, steps, opts) {
    if (!api || L.n === 0) return 0;
    if (L.dirty || L.links === null) buildLinks(L, state);
    if (L.n > capacity) return 0;

    const {
        repulsionForce = 0.05, wireForce = 1.0, velocityDecay = 0.05, theta = 0.5,
        leafSize = 64, maxLevel = 10, linkDistance = 2.0, maxDist = 80.0,
        maxSpeed = 1.0, gravity = 0.1
    } = opts || {};

    const linkN = Math.min(L.linkN, (api.links.length / 2) | 0);
    if (linkN > 0) {
        api.links.set(L.links.subarray(0, linkN * 2));
        const degrees = new Int32Array(L.n);
        for (let i = 0; i < linkN * 2; i++) degrees[L.links[i]]++;
        const weights = api.link_weights;
        for (let i = 0; i < linkN; i++) {
            const d = degrees[L.links[i * 2]] + degrees[L.links[i * 2 + 1]];
            weights[i] = d > 0 ? 1.0 / d : 1.0;
        }
    }

    const pos = api.points, vel = api.vel;
    pos.set(L.pos.subarray(0, L.n * 3));
    vel.set(L.vel.subarray(0, L.n * 3));

    api.setTheta(theta);
    api.setMaxSpeed(maxSpeed);

    const f = 1.0 - velocityDecay;
    const gravityStrength = gravity / Math.max(L.n, 16);

    for (let step = 0; step < steps; step++) {
        if (linkN > 0) api.linkForce(linkN, wireForce, linkDistance, L.n);
        const nodeCount = api.buildOctree(L.n, leafSize, maxLevel);
        api.calcMultibodyForce(L.n, nodeCount, maxDist);
        api.applyChargeForces(L.n, -repulsionForce);
        for (let p = 0; p < L.n * 3; p++) vel[p] -= pos[p] * gravityStrength;
        api.updateNodes(L.n, f);
    }

    L.pos.set(pos.subarray(0, L.n * 3));
    L.vel.set(vel.subarray(0, L.n * 3));
    return steps;
}

/** Particle positions, stride 3, in particle order. */
export function positions(L) {
    return L.pos.subarray(0, L.n * 3);
}

/** Cell index for each particle, so positions can be joined back to the netlist. */
export function cellIndices(L) {
    return L.cellOf.subarray(0, L.n);
}

/** Axis-aligned extent of the laid-out circuit, as [minX,minY,minZ,maxX,maxY,maxZ]. */
export function bounds(L) {
    const out = new Float32Array([Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity]);
    for (let p = 0; p < L.n; p++) {
        for (let k = 0; k < 3; k++) {
            const v = L.pos[p * 3 + k];
            if (v < out[k]) out[k] = v;
            if (v > out[k + 3]) out[k + 3] = v;
        }
    }
    return out;
}
