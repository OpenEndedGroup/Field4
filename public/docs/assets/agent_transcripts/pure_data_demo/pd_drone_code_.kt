// This text is the patch. Launch the box, then run one of these lines on its own:
freq 110
freq 330
cutoff 400
cutoff 3000
#N canvas 0 0 500 320 12;
#X obj 20 20 r freq;
#X msg 20 50 \$1 200;
#X obj 20 80 line~;
#X obj 20 110 phasor~;
#X obj 20 140 lop~ 1200;
#X obj 140 110 r cutoff;
#X obj 20 170 *~ 0.08;
#X obj 20 210 dac~;
#X obj 240 20 loadbang;
#X msg 240 50 220;
#X obj 240 80 print drone;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 2 0 3 0;
#X connect 3 0 4 0;
#X connect 5 0 4 1;
#X connect 4 0 6 0;
#X connect 6 0 7 0;
#X connect 6 0 7 1;
#X connect 8 0 9 0;
#X connect 9 0 1 0;
#X connect 9 0 10 0;
