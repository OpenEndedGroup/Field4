// The run ends at once; this line prints half a second later, beside line 3.
setTimeout(() => {
  print("half a second later")
}, 500)
print("the run is over")
