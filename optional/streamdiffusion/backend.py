"""Fixed-strength causal adapter matching the local studies, with CUDA tensor output."""
from collections import deque
from pathlib import Path
import torch


class DiffusionBackend:
    def __init__(self, root, width, height, settings):
        from omegaconf import OmegaConf
        from streamv2v.inference_common import merge_cli_config, load_generator_state_dict
        from streamv2v.inference import SingleGPUInferencePipeline
        from models.util import set_seed

        if width % 32 or height % 32:
            raise ValueError("Diffusion dimensions must be multiples of 32")
        set_seed(settings["seed"])
        self.root = Path(root)
        flags = dict(step=2, model_type="T2V-1.3B", height=height, width=width,
            t2v=False, profile=False, normalize_latents=True, fixed_noise_scale=True,
            use_taehv=True, use_tensorrt=settings["use_tensorrt"])
        self.config = merge_cli_config(str(self.root / "configs/local_rtx4090.yaml"), flags)
        self.schedule = list(OmegaConf.load(self.root / "configs/local_rtx4090.yaml").denoising_step_list)
        self.manager = SingleGPUInferencePipeline(self.config, torch.device("cuda", torch.cuda.current_device()))
        _, weights = load_generator_state_dict(str(self.root / "ckpts/wan_causal_dmd_v2v"))
        self.manager.pipeline.generator.load_state_dict(weights, strict=True)
        del weights
        self.encoder_forward = self.manager.pipeline.text_encoder.forward
        self.offloaded = settings["offload_text_encoder"]
        self.settings = None
        self.reset(settings)

    @torch.inference_mode()
    def reset(self, settings):
        from models.util import set_seed
        pipeline = self.manager.pipeline
        self.manager.reset_stream_state()
        pipeline.kv_cache2 = None
        pipeline.conditional_dict = None
        pipeline.vae._decode_latent_cache = None
        for module in pipeline.generator.modules():
            if hasattr(module, "evict_idx"):
                module.evict_idx = None
        first = max(1, round(settings["strength"] * 1000) - 100)
        self.config.denoising_step_list = [max(1, round(v * first / 700)) if v else 0
            for v in self.schedule[:2] + [0]]
        self.config.noise_scale = settings["strength"]
        pipeline._init_denoising_step_list(self.config, self.manager.device)
        # Encode before cache allocation, then free T5 GPU weights. Subsequent prompt edits
        # can encode on CPU; per-frame RGB and all VAE/DiT data remain on the GPU.
        if self.settings is None or settings["prompt"] != self.settings["prompt"]:
            context = self.encoder_forward(text_prompts=[settings["prompt"]])
            self.context = {k: v.to(self.manager.device) for k, v in context.items()}
        pipeline.text_encoder.forward = lambda **kwargs: dict(self.context)
        if self.offloaded:
            pipeline.text_encoder.to("cpu")
            torch.cuda.empty_cache()
        set_seed(settings["seed"])
        self.settings = settings
        self.first = True
        self.processed = 0
        self.pending_ids = deque()

    def capabilities(self):
        vae = self.manager.pipeline.vae
        decoder = getattr(vae, "_tensorrt_decoder", None)
        return dict(backend="diffusion", decoder=type(vae).__name__,
            taehv=hasattr(vae, "taehv"), tensorrt_requested=self.settings["use_tensorrt"],
            tensorrt_active=bool(getattr(decoder, "_engine_cache", {})) and not getattr(vae, "_tensorrt_failed", False),
            text_encoder_offloaded=self.offloaded, steps=2, fixed_strength=True)

    @torch.inference_mode()
    def process(self, frames, ids):
        manager, pipeline = self.manager, self.manager.pipeline
        images = torch.stack(frames, dim=1).unsqueeze(0)  # [1,C,T,H,W], [-1,1]
        noisy = manager._encode_noisy_latents(images, self.settings["strength"])
        if self.first:
            self.current_end = pipeline.frame_seq_length * 2
            prediction = manager.prepare_pipeline([self.settings["prompt"]], noisy, 0, self.current_end)
            self.first = False
            tags = ids[1:]  # causal decoder drops the leading RGB frame
        else:
            self.pending_ids.append(ids)
            start = self.current_end
            if start // pipeline.frame_seq_length >= manager.t_refresh:
                start = pipeline.kv_cache_length - pipeline.frame_seq_length
            self.current_end = start + pipeline.frame_seq_length
            prediction = pipeline.inference_stream(noisy, start, self.current_end,
                int(pipeline.denoising_step_list[0].item()))
            self.processed += 1
            if self.processed < len(pipeline.denoising_step_list):
                return []
            prediction = prediction[[-1]]
            tags = self.pending_ids.popleft()
        # Preserve a CUDA tensor rather than using the NumPy convenience decoder.
        video = manager._timed_stream_decode(prediction)
        video = (video[0].permute(0, 2, 3, 1).float() * 0.5 + 0.5).clamp(0, 1)
        if video.shape[0] != len(tags):
            raise RuntimeError(f"Decoder produced {video.shape[0]} frames for {len(tags)} source tags")
        return list(zip(video.unbind(0), tags))
