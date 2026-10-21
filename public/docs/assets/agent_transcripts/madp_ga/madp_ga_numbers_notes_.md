# Only the numbers mutate

The breeding rules are settings too. `ColorRpnBreeding` here allows only `num` mutations, never
borrows from the other parent (`seedSplice = 0.0`), and does not top the length up with random
words (`nudgeLength = false`). Every child is its parent with different numbers: the same rings
and the same diagonal bands, at new frequencies and colours.

It is a slower, gentler search, good for tuning a picture you already like. The demo's mix is
`ColorRpnBreeding.DEFAULT_WEIGHTS`: num 33%, var 19%, op 20%, splice 10%, copy 6%, drop 6%,
add 4.5%, swap 1.5%. Change the weights and re-run to try your own.
