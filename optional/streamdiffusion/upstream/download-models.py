"""Download only the files required by the local 1.3B inference setup."""
import os
from pathlib import Path

os.environ.setdefault("HF_XET_NUM_CONCURRENT_RANGE_GETS", "2")
os.environ.setdefault("HF_XET_FIXED_DOWNLOAD_CONCURRENCY", "2")

from huggingface_hub import snapshot_download
import urllib.request

root = Path(__file__).resolve().parent
snapshot_download(
    "Wan-AI/Wan2.1-T2V-1.3B",
    revision="37ec512624d61f7aa208f7ea8140a131f93afc9a",
    local_dir=root / "wan_models/Wan2.1-T2V-1.3B",
    allow_patterns=[
        "config.json",
        "Wan2.1_VAE.pth", "models_t5_umt5-xxl-enc-bf16.pth",
        "google/umt5-xxl/*",
    ],
    max_workers=1,
)
snapshot_download(
    "jerryfeng/StreamDiffusionV2",
    revision="2373eb2b39278b3a1aa174964a724ee78ead96f0",
    local_dir=root / "ckpts",
    allow_patterns=["wan_causal_dmd_v2v/*"],
    max_workers=1,
)
taehv = root / "ckpts/taew2_1.pth"
if not taehv.exists():
    temporary = taehv.with_suffix(".pth.part")
    urllib.request.urlretrieve(
        "https://github.com/madebyollin/taehv/raw/main/taew2_1.pth", temporary
    )
    temporary.replace(taehv)
