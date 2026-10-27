import afield.ai.learning.pen.*

// Every stroke of the word gets ten passes of the echoes pen's trembling line, each kept
// 3.5 pixels from the ink beside it. The pen proposes 32 next points at a time and a
// FollowGuide picks the one that keeps the gap without crossing anything. Try "michaux".
val word = HersheyFont.load("futural").text("FIELD", size = 90.0).apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
val hatching = Hatcher(listOf(word), PenModel.bundled("echoes"), passes = 10, gap = 3.5, seed = 1).finish()
hatching.forEach { it.color = vec(0.9, 0.92, 1.0, 1.0); it.thickness = 0.7 }
lines(listOf(word) + hatching, w = 520.0, h = 220.0, fitToSize = true)
