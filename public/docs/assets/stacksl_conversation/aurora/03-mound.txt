# 03-mound

<<you>>
In the middle, under the curtain, there's a big soft dark mound that reads like a second, nearer mountain — with stars in front of it. What is that shape, exactly? Show me what makes it, then make it read as open sky.
<<progress>>
Starting AI conversation
<<progress>>
TraceStackSLStudy finished (5288 ms)
<<progress>>
Waiting for model
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
That shape is **unlit sky, not another mountain**. The trace shows what creates the illusion:

- **Lines 8–10** define the aurora’s wavy lower edge.
- **Line 12**, `<height 0 0.035 ramp`, switches the aurora off over a narrow band. That gives the plain blue sky beneath it a mountain-like outline.
- **Line 28** adds stars afterward, so they can appear across that region.
- The actual black hills are separate, on **lines 30–34**.

I’ll replace the abrupt lower cutoff with a broad, smooth wash of aurora light into the open sky, leaving the black hills unchanged.
<<progress>>
InspectStackSLStudy finished (2 ms)
<<progress>>
PatchStackSLStudy finished (1320 ms)
<<progress>>
Waiting for model
<<progress>>
Completed
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
_r3-89349350ef01: what each stage leaves on top of the stack at 0.00 s_

![tool image](images/7d749b1c3ac86d46aca2.png)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
_r4-4b8f100a41d2: frames at 0.00, 6.00, 12.0 s; second row r3-89349350ef01, third row the difference_

![tool image](images/ebadaa72e99d2b64aaee.png)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
It was **plain blue sky outlined by the aurora’s abrupt lower cutoff**, not a second mountain. The trace above shows that outline forming at line 12, before the actual hills are added.

Replaced the narrow cutoff:
```stacksl
<height 0 0.035 ramp
```
with a broad atmospheric fade:
```stacksl
-0.28 0.06 <height smoothstep
```

The curtain now dissolves into open sky instead of outlining a dark mound. Stars and the still, pure-black hills are unchanged. Checked the rendered result at **0, 6, and 12 seconds**; compilation passed without warnings.