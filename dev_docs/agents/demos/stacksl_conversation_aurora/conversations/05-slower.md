# 05-slower

<<you>>
I took C in the card. Now slow the whole ripple to about half speed, keeping everything else exactly as it is, and show me it really moves about half as much.
<<progress>>
Starting AI conversation
<<progress>>
InspectStackSLStudy finished (1 ms)
<<progress>>
Waiting for model
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
I’ll halve all five time rates—edge motion, fold gathering, billowing, and brightness variation—without changing anything else. Then I’ll compare motion over the same interval and check that the slowed version at 4 seconds matches the original at 2 seconds.
<<progress>>
ObserveStackSLStudy finished (408 ms)
<<progress>>
SetStackSLNumbers finished (2225 ms)
<<progress>>
Waiting for model
<<progress>>
Completed
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
_r5-e63307b6d523: frames at 0.00, 1.00, 2.00 s_

![tool image](images/4cea81a9eb93ac56efed.png)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
_r6-47a28af6de3b: frames at 0.00, 1.00, 2.00, 4.00 s; second row r5-e63307b6d523, third row the difference_

![tool image](images/79296ea727c07fc1ba77.png)
<<ai>>
<<model:{"provider":"openai","requestedModel":"gpt-6.1-sol","responseModel":"gpt-6.1-sol"}>>
Done—**C now runs at half speed**. Only its five time rates changed; colours, folds, stars, sky fade, and black hills remain unchanged.

Measured frame-to-frame change over equal intervals:

| Interval | Before | Slowed | Ratio |
|---|---:|---:|---:|
| 0–1 s | 1.2076 | 0.6065 | **50.2%** |
| 1–2 s | 1.2107 | 0.6040 | **49.9%** |

These measure average pixel-colour change, not physical displacement. Halving every time rate also means the new animation at **4 seconds** reaches the original’s **2-second** state, visible in the previews.

The starting frame is pixel-identical. Compilation and checks passed without warnings.