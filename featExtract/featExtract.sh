#!/bin/bash

set -eu

# add MADA to the perl path
export PERL5LIB=$PERL5LIB:$MADA_HOME

# get mada features
perl $MADA_HOME/common-tasks/extractFeaturesIntoColumns.pl feats=pos,cas,asp,num,per,gen,stt,normword,noanalysis,gloss file=$1.mada > $1.madaFeats

# feature extraction
python featExtraction.py $1.bio $1.madaFeats  ./lexicons/NEList.txt ./lexicons/NonNEList.txt lexicons/ArabicStopWordList.txt
