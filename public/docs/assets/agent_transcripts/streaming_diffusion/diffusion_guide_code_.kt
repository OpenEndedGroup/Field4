markdown(
    """
    # Streaming diffusion

    Run **2 · renderer**, then **3 · spinning curves**.
    Source is on the left; the returned stylized image is on the right.
    The example starts with charcoal. Enter any text in the prompt override.
    Open the diffusion controls to change material, strength, reprojection,
    history blending and experimental noise advection.

    The local model worker is optional and requires Linux/NVIDIA.
    On other platforms the curves box explains availability without starting it.
    Set `FIELD_STREAMDIFFUSION_ROOT` before starting Field after completing the setup.

    [Setup and RTT integration](https://openendedgroup.com/Field4/streaming_diffusion/)
    """
        .trimIndent(),
)
