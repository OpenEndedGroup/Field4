#ifndef AFIELD_FLOW_TILES
#define AFIELD_FLOW_TILES
int flowFloorMod(int value, int divisor) { return ((value % divisor) + divisor) % divisor; }
int flowTileSlot(ivec2 tile, ivec2 shape) {
    return flowFloorMod(tile.y, shape.y) * shape.x + flowFloorMod(tile.x, shape.x);
}
// Compare this slot's header before sampling it; modulo alone wraps the WORLD.
bool flowTileResident(ivec4 header, ivec2 tile) {
    return header.w != 0 && all(equal(header.xy, tile));
}
ivec2 flowTilePixel(int slot, ivec2 shape, int cells, int halo, ivec2 cell) {
    return ivec2(slot % shape.x, slot / shape.x) * (cells + 2 * halo) + halo + cell;
}
#endif
