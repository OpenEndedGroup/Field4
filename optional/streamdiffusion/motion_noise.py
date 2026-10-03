"""Optional motion-anchored latent noise; CUDA tensors only, no optical-flow estimation."""
import torch
import torch.nn.functional as F


class MotionNoise:
    def __init__(self):
        self.reset()

    def reset(self):
        self.previous = None
        self.previous_id = None
        self.motion_frames = 0

    @torch.inference_mode()
    def sample(self, latents, motions, ids, amount):
        # Wan reduces four RGB frames to one temporal latent. Use the last RGB
        # correspondence in each group; the first five-frame group also has its
        # leading latent. This is a spatial approximation, not VAE feature flow.
        if amount <= 0:
            self.reset()
            return torch.randn_like(latents)
        shape = latents[:, 0].shape  # [B,C,Hlatent,Wlatent]
        if self.previous is not None and self.previous.shape != shape:
            self.reset()
        selected = []
        count = latents.shape[1]
        endpoints = [0, len(ids) - 1] if count == 2 and len(ids) == 5 else [len(ids) - 1]
        if len(endpoints) != count:
            raise ValueError("Unexpected causal RGB/latent temporal ratio")
        for i, (tag, motion) in enumerate(zip(ids, motions)):
            fresh = torch.randn(shape, device=latents.device, dtype=torch.float32)
            state = fresh
            if self.previous is not None and motion is not None and motion[1] == self.previous_id:
                field = F.interpolate(motion[0].permute(2, 0, 1).unsqueeze(0),
                    size=shape[-2:], mode="nearest")
                finite = torch.isfinite(field).all(dim=1, keepdim=True)
                uv = field[:, :2]
                valid = finite & (uv >= 0).all(dim=1, keepdim=True) & (uv <= 1).all(dim=1, keepdim=True)
                confidence = torch.nan_to_num(field[:, 2:3], nan=0.0).clamp(0, 1)
                reactive = torch.nan_to_num(field[:, 3:4], nan=1.0).clamp(0, 1)
                rho = (amount * confidence * (1 - reactive) * valid).clamp(0, 1)
                grid = torch.nan_to_num(uv, nan=0.5, posinf=0.5, neginf=0.5).clamp(0, 1).permute(0, 2, 3, 1) * 2 - 1
                # Nearest sampling preserves marginal noise variance. Bilinear
                # noise warps lose variance and change the denoising distribution.
                old = F.grid_sample(self.previous, grid, mode="nearest", padding_mode="border", align_corners=False)
                state = rho.sqrt() * old + (1 - rho).sqrt() * fresh
                self.motion_frames += 1
            self.previous, self.previous_id = state, tag[0]
            if i in endpoints:
                selected.append(state.to(latents.dtype))
        return torch.stack(selected, dim=1)
