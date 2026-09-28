// Launch, then run "in 41": [+ 1] sends 42 to [s out] and [print] prints it.
in 41
in 99
#N canvas 0 0 400 250 12;
#X obj 20 20 r in;
#X obj 20 60 + 1;
#X obj 20 100 s out;
#X obj 120 100 print out;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 1 0 3 0;
