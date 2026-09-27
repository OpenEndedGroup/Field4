# 03-water

<<you>>
I adopted B in the card and sped the drift up myself with a slider. The amber water still feels like one flat tone. Give it a slow warmth — light moving through the water — without changing the ink at all, and show me the ink is untouched.
<<progress>>
Starting AI conversation
<<progress>>
InspectStackSLStudy finished (3 ms)
<<progress>>
CaptureRenderWindowRequest finished (36 ms)
<<progress>>
Waiting for model
<<progress>>
Evaluating image comparison process
**Evaluating image comparison process**

I'm looking into how to automatically compare images and patch differences. I need to test a subset of ink with an exact additional mask, using the allowedRegion and focusing on square areas. It would be interesting to compare stages after explicitly selecting with an allowedRegion of [0,0,1,1]. I'm wondering if I can create a diagnostic variant that subtracts the original masked images from an expected black stage using the compare stage as a baseline.

Next tool: `PatchStackSLStudy`
<<progress>>
PatchStackSLStudy finished (1295 ms)
<<progress>>
CompareStackSLStudy finished (627 ms)
<<progress>>
CaptureRenderWindowRequest finished (537 ms)
<<progress>>
Completed
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
_afield.four render window 'StackSL study', captured live. Describe what you actually see, then say whether it matches what was asked for._

![tool image](images/d7285914cb1bbf0b45ef.jpg)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
_r5-cfb7e379eb87: frames at 0.00, 8.00, 20.0, 40.0 s; second row r4-59e28da4f79d, third row the difference_

![tool image](images/b9cd4aa22d179dff673b.png)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
_r4-59e28da4f79d → r5-cfb7e379eb87: before | after | difference, per time_

![tool image](images/8ef3b9b4c73d82e755d1.png)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
_afield.four render window 'StackSL study', captured live. Describe what you actually see, then say whether it matches what was asked for._

![tool image](images/e844737c466692f654a9.jpg)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
Added slow **honey highlights and copper shadows** through the clear water. The live view now has soft, uneven illumination around the indigo bloom.

**Ink preservation checks:**
- Your `0.06t` speed and B’s threads are unchanged.
- The original rendering stage matched exactly at **0, 8, 20, and 40 seconds**: maximum difference **0**.
- The final lighting explicitly keeps the original color wherever any ink is present—including faint edges. The final-image difference maps above show changes in the water, with the ink region black.

Compiled and validated without warnings or invalid pixels.