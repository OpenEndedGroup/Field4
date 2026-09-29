// [osc 440] is not a Pd object ([osc~ 440] is). Launch: the error is on its line.
#N canvas 0 0 400 250 12;
#X obj 20 20 osc 440;
#X obj 20 60 dac~;
#X connect 0 0 1 0;
