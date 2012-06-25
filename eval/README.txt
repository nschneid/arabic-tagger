The evaluation pipeline assumes that the tagger outputs a .result file
with the following format:

word1 gold-tag predicted-tag
word2 gold-tag predicted-tag
word3 gold-tag predicted-tag
..

Use the following command, which reads in a taggedCorp.result file and
generates a taggedCorp.score file:

./evalPipeline.sh taggedCorp

The CoNLL evaluation script (http://www.cnts.ua.ac.be/conll2003/ner/) 
is used to compute the scores.
