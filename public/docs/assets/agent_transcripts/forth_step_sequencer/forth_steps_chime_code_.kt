: chime  tri 0.5 dur 0.5 gain ;
90 bpm
seq: bells  chime [ c5 e5 g5 b5 ] iter index note | | 0.2 dur [ c3 g3 eb3 ] [ 6 3 1 ] wchoose note | ;seq
7 seed bells 8 run-steps
7 seed bells 32 render-steps
