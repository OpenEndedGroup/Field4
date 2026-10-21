# Long memory ecology

The previous frame steers where it is read from next, two slow waves add a drift, and `ema` keeps
the result as four memories. As on the page it looks like fine moving grain. The line
`dup average … + +` adds each pixel's average back every frame, and `mirror` folds the growing
values. Delete that line and it fills slowly into a pale moving fog instead.
