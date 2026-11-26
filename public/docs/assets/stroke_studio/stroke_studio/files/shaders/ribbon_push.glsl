layout(push_constant) uniform DrawPush {
    vec4 camera;
    vec4 scene;
    vec4 style; // spill gain, stroke scale
    vec4 drawStyle; // live width, weight, world displacement, time
    StrokeBuffer strokes;
} pc;
struct RibbonPayload {
    uint ids[32];
};
taskPayloadSharedEXT RibbonPayload activeRibbons;
