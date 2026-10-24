// The marker lands on line 4, inside mean, where reduce failed --
// not on line 7, where mean was called.
function mean(values) {
  return values.reduce((a, b) => a + b) / values.length
}

mean([])
