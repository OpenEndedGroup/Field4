// Model-free device check: real FP8 tensor-core GEMMs compared with exact CPU sums.
#include "kernels.h"
#include "numeric.h"
#include <cstring>

int runSelftest(const std::string& shaderDir) {
  vk::Context context;
  printf("device: %s, %u SMs\n", context.deviceName().c_str(), context.smCount());
  nr::Kernels kernels(context, shaderDir);
  constexpr uint32_t rows = 64, k = 64, n = 64;
  nr::Activation input, output;
  input.format = nr::Format::E4; input.rows = input.allocRows = rows; input.channels = k;
  output.format = nr::Format::F16; output.rows = output.allocRows = rows; output.channels = n;
  input.buffer = context.createBuffer(rows * k, false, "selftest input");
  output.buffer = context.createBuffer(rows * n * 2, false, "selftest output");
  vk::Buffer weights = context.createBuffer(k * n, false, "selftest weights");
  std::vector<uint8_t> a(rows * k), b(k * n), packedB(k * n);
  for (uint32_t r = 0; r < rows; ++r)
    for (uint32_t c = 0; c < k; ++c)
      a[r * k + c] = num::e4m3FromF16Bits(num::f16Bits((int((r * 13 + c * 7) % 9) - 4) / 16.0f));
  for (uint32_t r = 0; r < k; ++r)
    for (uint32_t c = 0; c < n; ++c)
      b[r * n + c] = num::e4m3FromF16Bits(num::f16Bits((int((r * 5 + c * 11) % 7) - 3) / 16.0f));
  // Both kernel routes use [K/32][N][32] tile-major weights.
  for (uint32_t r = 0; r < k; ++r)
    for (uint32_t c = 0; c < n; ++c)
      packedB[((r / 32) * n + c) * 32 + r % 32] = b[r * n + c];
  context.upload(input.buffer, a.data(), a.size());
  context.upload(weights, packedB.data(), packedB.size());
  context.fillZero(output.buffer);
  nr::GemmFp8Args args;
  args.input = &input; args.weights = &weights; args.Nmatrix = n;
  args.output = &output; args.quantize = false;
  args.rows = rows; args.K = k; args.N = n;
  VkCommandBuffer commands = context.beginCommands();
  kernels.beginProfile(commands, 16);
  kernels.gemmFp8(commands, args);
  context.endAndSubmit(commands, true);
  auto profile = kernels.endProfile();
  for (const auto& entry : profile)
    printf("kernel: %s (%.3f ms)\n", entry.label.c_str(), entry.milliseconds);
  auto result = context.download(output.buffer, rows * n * 2);
  size_t mismatches = 0;
  for (uint32_t r = 0; r < rows; ++r) {
    for (uint32_t c = 0; c < n; ++c) {
      float sum = 0;
      for (uint32_t inner = 0; inner < k; ++inner)
        sum += num::e4m3ToF32(a[r * k + inner]) * num::e4m3ToF32(b[inner * n + c]);
      uint16_t actual;
      memcpy(&actual, result.data() + (r * n + c) * 2, 2);
      // All products and sums in this fixture are exactly representable in binary16.
      if (actual != num::f16Bits(sum)) {
        if (mismatches < 5) printf("mismatch [%u,%u]: %g expected %g\n", r, c, num::f16ToF32(actual), sum);
        ++mismatches;
      }
    }
  }
  context.destroyBuffer(input.buffer);
  context.destroyBuffer(output.buffer);
  context.destroyBuffer(weights);
  printf("FP8 GEMM: %u values checked, %zu mismatches: %s\n", rows * n, mismatches, mismatches ? "FAIL" : "PASS");
  return mismatches ? 1 : 0;
}
