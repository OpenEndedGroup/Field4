# Precision fixture

`precision-ramp.mkv` is a repository-authored lossless FFV1 test movie, not a captured render.
It has two frames at one frame/second, full-range BT.709, YUV444P10LE. Both halves are 1024x64:
left ascends through every code 0..1023; right descends. Chroma is constant at 512. The second frame reverses both ramps, exercising temporal accumulation.

To regenerate, write two raw frames with Python `struct.pack('<2048H', *range(1024),
*reversed(range(1024)))` repeated for 64 luma rows, followed by two 2048x64 planes of 512. Reverse the luma codes for the second frame.
Encode the resulting raw YUV444P10LE file with explicit full range on input AND output:

```sh
ffmpeg -f rawvideo -pixel_format yuv444p10le -video_size 2048x64 -framerate 1 \
  -color_range pc -colorspace bt709 -i input.yuv -c:v ffv1 -pix_fmt yuv444p10le \
  -level 3 -color_range pc -colorspace bt709 -y precision-ramp.mkv
```

Decode back to full-range raw and verify exactly 1024 distinct ascending luma codes in the
first row before using the fixture. A filter/output range mismatch can bake clipping into
the fixture itself, invalidating a precision test. The smoke test uses real FFmpeg decoding,
Vulkan plane uploads, the movie fragment shader and RGBA16F eye-target readback.
