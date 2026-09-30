inline fun <T> eachWhile(items: List<T>, action: (T) -> Unit) {
    var i = 0
    while (i < items.size) {
        val item = items[i]
        when {
            item == null -> {}
            else -> action(item)
        }
        i++
    }
}

inline fun <T> visitAll(groups: List<List<T>>, crossinline action: (T) -> Unit) {
    eachWhile(groups) { group -> eachWhile(group) { action(it) } }
}
