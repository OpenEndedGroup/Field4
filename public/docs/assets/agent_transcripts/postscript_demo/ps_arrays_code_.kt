/fib {dup 2 ge {1 sub dup 1 sub fib exch fib add} {} ifelse} def
[0 1 10 {fib} for] ==
[0 1 9 {2 copy add} repeat] ==
/reverse {[exch dup length 1 sub -1 0 {1 index exch get exch} for pop]} def
[11 22 33] reverse ==
0 { 1 add dup 10 ge {exit} if } loop
