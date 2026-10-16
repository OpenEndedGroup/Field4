# 02-still-hills

<<you>>
The hills must stay perfectly still and black. Check whether anything moves below the horizon, and if it does, fix it without touching the sky.
<<progress>>
Starting AI conversation
<<progress>>
InspectStackSLStudy finished (1 ms)
<<progress>>
Waiting for model
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
The hill shape has no time dependence, and the aurora is masked out below it, so the hills should already be still. They aren’t pure black, though, and the softened edge blends sky colour just below the hill line. I’ll make everything below that line exactly black while preserving the sky above it.
<<progress>>
StackSLStudyGuide finished (0 ms)
<<progress>>
PatchStackSLStudy finished (1444 ms)
<<progress>>
Waiting for model
<<progress>>
Completed
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
_r3-89349350ef01: frames at 0.00, 6.00, 12.0 s; second row r2-182508f78d66, third row the difference_

![tool image](images/2ad4616b0d96c0d9081d.png)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
The hills were already motionless, but slightly blue-black with a blended edge. They are now **exactly black below the unchanged hill line**, with no time-dependent contribution.

Verified at **0, 6, and 12 seconds**. The comparison shows changes only below the hill line; the sky and aurora are untouched. Compilation and checks passed.