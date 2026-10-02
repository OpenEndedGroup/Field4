#pragma once
#include <stdint.h>
#define F4NR_API __attribute__((visibility("default")))
#ifdef __cplusplus
extern "C" {
#endif
// ABI 1; all calls return 0 on success, error() describes the last failure on
// this thread.
typedef struct {
  uint64_t image, view;
  uint32_t format, width, height, layout; // Actual Vulkan layout, after Field's logical-layout mapping.
} F4NrImage;
typedef struct {
  uint32_t seed;
  int32_t style;
  float tone, structure;
  float skin;
  uint32_t auto_mask;
  float intensity;
  uint32_t encoding;
  float paper_white;
  uint32_t temporal, history_valid;
  float motion_sign;
  float blend_scale;
  uint32_t preserve_alpha, pad0, pad1;
} F4NrControls;
F4NR_API uint32_t f4nr_abi(void);
F4NR_API uint32_t f4nr_image_size(void);
F4NR_API uint32_t f4nr_controls_size(void);
F4NR_API const char *f4nr_error(void);
F4NR_API const char *f4nr_extensions(void);
F4NR_API int f4nr_probe(uint64_t instance, uint64_t physical, void **plan);
// Merge core features into an existing chain, append extension structs without
// duplicate sTypes.
F4NR_API int f4nr_apply(void *plan, uint64_t features2);
F4NR_API void f4nr_plan_close(void *plan);
// Images: color, output, optional motion/depth (both required for temporal).
F4NR_API int f4nr_create(uint64_t instance, uint64_t physical, uint64_t device,
                         uint32_t family, const char *model,
                         const char *shaders, const F4NrImage *images,
                         void **pass);
F4NR_API int f4nr_record(void *pass, uint64_t command,
                         const F4NrControls *controls);
// Destroy only after Field's completion fence; borrowed images/device are never
// destroyed.
F4NR_API int f4nr_destroy(void *pass);
// Diagnostic head access: padded f32 RGBA; caller must synchronize any
// copy/readback.
F4NR_API uint64_t f4nr_head_buffer(void *pass);
F4NR_API uint64_t f4nr_allocated_bytes(void *pass);
F4NR_API uint32_t f4nr_padded_width(void *pass);
#ifdef __cplusplus
}
#endif
