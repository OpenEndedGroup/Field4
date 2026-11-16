/branch { % length depth branch
  dup 0 gt {
    exch dup 0 0 moveto 0 exch lineto stroke
    dup 0 exch translate
    0.7 mul exch 1 sub
    gsave 25 rotate 2 copy branch grestore
    gsave -25 rotate branch grestore
  } { pop pop } ifelse
} def

0.55 0.85 0.4 setrgbcolor
2 setlinewidth
300 50 translate
100 8 branch
