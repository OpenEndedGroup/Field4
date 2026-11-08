import afield.graphics.text.sentences.*
import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode

val measured = SentenceTexts.all().flatMap { it.measure() }
val words = measured.map { it.length }
val series = [
    "free random reordering (mean of 100)" to measured.map { it.lengths.freeMean },
    "fixed order random reordering (mean of 100)" to measured.map { it.lengths.fixedOrderMean },
    "as written" to measured.map { it.lengths.observed.toDouble() },
    "shortest possible" to measured.map { it.lengths.optimal.toDouble() },
]
val plot = Plotly.plot {
    for ((name, values) in series) {
        scatter {
            this.name = name
            x.set(words)
            y.set(values)
            mode = ScatterMode.markers
            marker { size = 4.0 }
        }
    }
    layout {
        title = "Total dependency length, ${measured.size} sentences from five texts"
        xaxis { title = "words in the sentence" }
        yaxis { title = "total dependency length" }
    }
}
plot.darkMode()
// darkMode() places the legend beside the plot; put it inside, over the empty upper left.
plot.layout {
    legend {
        x = 0.02
        y = 0.98
    }
}
display(plot)
