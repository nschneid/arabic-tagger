#!/bin/bash
#using getopts

python ./convert2CONLLEval.py $1.result $1.conll
perl ./conlleval.pl <  $1.conll > $1.score
