// `saved {}` is per-box persistence: assign to it and it is on disk, read it next
// session and it is still there. The file is <document dir>/<box id>/runs.json.
//
// Run this box a few times in a NORMAL session and the count climbs across restarts.
// Run it in a READ-ONLY session and it climbs within the run and then forgets: the
// write is refused, and `~/.afield/logs/save_reload.log` says so with a
// `write-refused-read-only` line naming this property.
//
// Before 2026-09-07 the read-only case wrote the file anyway — including on the very
// first *read*, because with no file yet `saved {}` computes the default and writes it.
var runs by saved { 0 }

runs = runs + 1
println("this box has run $runs time(s)")
