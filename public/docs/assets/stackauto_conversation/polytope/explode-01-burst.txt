# explode-01-burst

<<you>>
In the explode slot (the vertex shader): make the polytope burst. Every few seconds the faces should fly out from the centre and come back together, and faces nearer the top should go first, so it reads as a wave passing down the solid rather than everything at once.
<<progress>>
Starting AI conversation
<<progress>>
StackAutoSlotGuide finished (3 ms)
<<progress>>
InspectStackAutoSlot finished (2 ms)
<<progress>>
ListContentsRequest finished (204 ms)
<<progress>>
CaptureRenderWindowRequest finished (41 ms)
<<progress>>
StackAutoSlotGuide finished (1 ms)
<<progress>>
StackAutoSlotGuide finished (0 ms)
<<progress>>
StackAutoSlotGuide finished (0 ms)
<<progress>>
StackAutoSlotGuide finished (0 ms)
<<progress>>
StackAutoSlotGuide finished (0 ms)
<<progress>>
StackAutoSlotGuide finished (0 ms)
<<progress>>
EditStackAutoSlot finished (434 ms)
<<progress>>
CompileKotlinRequest finished (70 ms)
<<progress>>
ReloadShadersRequest finished (175 ms)
<<progress>>
Completed
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
_afield.four render window 'Polytope', captured live. Describe what you actually see, then say whether it matches what was asked for._

![tool image](images/3319ceca77b94353c116.jpg)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
_after the edit — stack.auto.explode in your shader: the window, as it is drawing now, at the slot's current numbers — everything the window draws, not only this module_

![tool image](images/9710a97f232f231153dd.png)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
_Post-shader-reload screenshot of the afield.four render window, captured after a rendered frame. Inspect it against what the user asked for, together with any earlier screenshot. If the requested visual result is not achieved, edit and reload again before finalizing._

![tool image](images/099f0483088342f01175.jpg)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6-astra","responseModel":"gpt-6-astra"}>>
Updated `explode` with a **4.8-second burst cycle**:

- Faces move outward whole, then ease back together.
- Higher faces lead, sending the wave downward.
- A short assembled pause separates bursts.

The captures show both the assembled solid and a burst phase with the top closed while lower faces remain spread apart. Colour is unchanged.

Kotlin compilation and shader reload both succeeded.