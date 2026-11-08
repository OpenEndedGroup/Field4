import afield.graphics.text.sentences.*

val texts = SentenceTexts.all()
display(
    "ratios",
    violin(
        *texts.map { t -> t.key to t.measure().filter { it.length >= 8 }.map { it.ratio } }.toTypedArray(),
        references = List(texts.size) { 1.0 },
        yTitle = "as written ÷ free random mean",
    ),
)
