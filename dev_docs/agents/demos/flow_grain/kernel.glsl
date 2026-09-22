// Common storage contract for the editable fluid stages.
#include "flow/tiles.glsl"
layout(std430, buffer_reference) buffer Field { vec4 v[]; };
layout(std430, buffer_reference) readonly buffer Tiles { ivec4 v[]; };
layout(push_constant) uniform Push {
    Field source; // Stage input; xy velocity or x scalar; w generation bits.
    Field target; // Stage output, on the same graphics queue.
    Field auxiliary; // Divergence or pressure, depending on the stage.
    Tiles tiles; // TileResidency.pack: world x/y, generation, occupied.
    vec4 clock; // x seconds; y step seconds; zw reserved.
    vec4 emitter; // xy force origin; z strength; w reserved.
} pc;

const int N = 24; // Cells along each tile; matches resources box.
const ivec2 SHAPE = ivec2(5, 5); // Bounded residency; no periodic world wrapping.
const float TILE = 4.; // World units per tile.
const float H = TILE / float(N); // World units per fluid cell.
const int CELLS = 25 * N * N;

bool rock(vec2 p) {
    // A thin ledge and a disc, evaluated exactly for drawing and approximately for the grid.
    return (abs(p.y + 2.) < .10 && abs(p.x) < 4.) || length(p - vec2(2.4, 1.2)) < .8;
}
ivec2 worldCell(int index) {
    int slot = index / (N * N), local = index % (N * N);
    return pc.tiles.v[slot].xy * N + ivec2(local % N, local / N);
}
vec2 worldPosition(int index) { return (vec2(worldCell(index)) + .5) * H; }
int cellIndex(ivec2 cell) {
    ivec2 local = ivec2(flowFloorMod(cell.x, N), flowFloorMod(cell.y, N));
    ivec2 tile = (cell - local) / N;
    int slot = flowTileSlot(tile, SHAPE);
    if (!flowTileResident(pc.tiles.v[slot], tile)) return -1;
    return slot * N * N + local.y * N + local.x;
}
vec4 readCell(Field field, ivec2 cell) {
    int index = cellIndex(cell);
    if (index < 0 || rock((vec2(cell) + .5) * H)) return vec4(0.);
    vec4 value = field.v[index];
    return floatBitsToInt(value.w) == pc.tiles.v[index / (N * N)].z ? value : vec4(0.);
}
vec4 sampleField(Field field, vec2 p) {
    vec2 grid = p / H - .5;
    ivec2 cell = ivec2(floor(grid));
    vec2 f = fract(grid);
    return mix(mix(readCell(field, cell), readCell(field, cell + ivec2(1,0)), f.x),
               mix(readCell(field, cell + ivec2(0,1)), readCell(field, cell + ivec2(1)), f.x), f.y);
}
void storeCell(int index, vec3 value) {
    pc.target.v[index] = vec4(value, intBitsToFloat(pc.tiles.v[index / (N * N)].z));
}
