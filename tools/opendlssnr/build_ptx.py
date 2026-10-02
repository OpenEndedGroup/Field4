"""Generate the same PTX variants as build_shaders.ps1, on any platform."""
import itertools
from pathlib import Path
import subprocess
import sys

source = Path(sys.argv[2]).resolve()
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)

def emit(script, args, name, extra=()):
    subprocess.run([sys.executable, str(source / (script + ".py")),
                    *map(str, args), str(out / (name + ".ptx")), *map(str, extra)],
                   check=True, stdout=subprocess.DEVNULL)

for k in (64, 128, 256):
    emit("mlp_e4m3", [k], f"mlp_e4m3_K{k}", [3, 1, 1])
for k in (64, 128, 256, 512):
    emit("qkv_e4m3", [k], f"qkv_e4m3_K{k}", [80])
for k, f in itertools.product((32, 64, 128, 256, 512, 1024), (5, 13, 4, 8, 6)):
    emit("gemm2_e4m3", [k, f], f"gemm2_e4m3_K{k}_f{f}")
for c, r, groups in ((64, 4, 0), (128, 4, 0), (256, 3, 80), (256, 4, 64)):
    emit("ffn_e4m3", [c, r], f"ffn_e4m3_C{c}_R{r}", [groups])
    emit("ffn_e4m3", [c, r], f"ffn_e4m3_C{c}_R{r}_proj", [groups, 1])
for k, f, p, s in ((4096, 1, 32, 4), (1024, 0, 16, 2), (1024, 1, 8, 4), (1024, 0, 8, 4)):
    emit("gemmt_e4m3", [k, f, p, s], f"gemmt_e4m3_K{k}_f{f}_p{p}_s{s}")
for s, f in ((4, 4), (2, 8), (4, 8)):
    emit("reduce_e4m3", [s, f], f"reduce_e4m3_s{s}_f{f}")
for k, f, s in ((1024, 6, 1), (4096, 5, 4), (1024, 8, 2), (1024, 5, 4)):
    name = f"gemmv_e4m3_K{k}_f{f}_s{s}"
    emit("gemmv_e4m3", [k, f, s], name)
    emit("gemmv_e4m3", [k, f, s], name + "_m96", [4, 6, 0, 0, 0, 96])
for padded in (64, 128, 192, 256):
    emit("global_attention_e4m3", [padded], f"global_attention_e4m3_p{padded}")
emit("global_attention_stream_e4m3", ["normalize"], "global_normalize_e4m3")
emit("global_attention_stream_e4m3", ["attention"], "global_attention_stream_e4m3", [4])
for k, f in ((512, 4), (512, 5), (512, 13), (256, 5), (4096, 5), (1024, 8), (1024, 5)):
    emit("gemmv_e4m3", [k, f, 1], f"gemmv_e4m3_K{k}_f{f}_s1_m96", [4, 6, 0, 0, 0, 96])
for f in (2, 66, 74, 130, 48):
    emit("block32_e4m3", [f], f"block32_e4m3_f{f}")
print(f"Generated {len(list(out.glob('*.ptx')))} PTX kernels in {out}")
