# 04-export

<<you>>
That's the one. Export a still and a short clip of it into the document so I can use them elsewhere.
<<progress>>
Starting AI conversation
<<progress>>
InspectStackSLStudy finished (2 ms)
<<progress>>
Waiting for model
<<progress>>
Estimating export time
**Estimating export time**

I’m trying to figure out how to export the maximum minutes for my project. I think I should choose two PNG files at 2048 resolution and probably use GPU for the export. My estimation tool indicates it might take 20 seconds for the PNGs and around 12 seconds for the clip at 24 fps with a max of 2 minutes. I need to finalize everything based on this estimate!

Next tool: `ExportStackSLStudy`
<<progress>>
ExportStackSLStudy finished (332 ms)
<<progress>>
ExportStackSLStudy finished (410 ms)
<<progress>>
InspectStackSLStudy finished (1 ms)
<<progress>>
Completed
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
Exports are going into the document’s **`files/`** folder:

- **Still — ready:** `indigo-amber-still.png`  
  2048 × 2048, captured at 20 seconds.
- **Clip — rendering:** `indigo-amber-clip.mp4`  
  512 × 512, 12 seconds at 24 fps, covering seconds 20–32. Estimated render time: about 80 seconds.

Both use the approved version unchanged. Clip progress is visible in the shader card; the MP4 will appear when rendering finishes.