# this scrip modifies the output of the NE tagger to make it readable 
# for the standard conll evaluator

import string 
import sys

file = open(sys.argv[1])
conllFile = open(sys.argv[2],"w")


while 1:
    line = file.readline()
    if line == "":
        break
    lineList = line.strip().split()

    if len(lineList) < 2:
        conllFile.write(line)
        continue
    
    # index 1 is reference, index 2 is hyp
    hyp = lineList[2]
    ref = lineList[1]
    wd = lineList[0]
    newLine = wd + " TOK " + ref + " " + hyp + "\n"
    conllFile.write(newLine)
    
    
    
