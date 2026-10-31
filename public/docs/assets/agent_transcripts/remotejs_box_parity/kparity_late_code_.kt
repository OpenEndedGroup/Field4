// {{ remoteAR }}
// The run ends at once; line 4 prints half a second later.
setTimeout(
    {
        println("half a second later")
    },
    500,
)
println("the run is over")
