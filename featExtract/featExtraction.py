"""
    AQMAR Arabic Tagger: Sequence tagger with cost-augmented structured perceptron training
    Copyright (C) 2012  Behrang Mohit, Nathan Schneider, Rishav Bhowmick, Kemal Oflazer, and Noah A. Smith

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
"""
import sys
import string
import codecs

# python featExtraction.py /home1/behrang/ner/projection/compoundNEs/grow-diagAlnProjArCorpWithAllIDTNEs.utf8 /home1/behrang/ner/projection/compoundNEs/grow-diagAlnProjArCorpWithAllIDTNEs.madaFeats  /home1/behrang/ner/wiktionaries/dblinked-ArEnNEList-byRishav.txt /home1/behrang/ner/wiktionaries/dblinked-ArEnNonNEs-byRishav.txt /home1/behrang/ner/code/mergedArabicStopWordList.txt

def getAffixFeats(wd):
    wdLen = len(wd)
    affixList = []
    fLine = ""
    for i in range(1,4):
        affixList.append(wd[:i])
        affixList.append(wd[-i:])
        fLine += (wd[:i] + "\t")
        fLine += (wd[-i:]+ "\t")

    if len(wd) > 5:    
        affixList.append(wd[2:4])
        affixList.append(wd[1:4])
        affixList.append(wd[-4:-2])
        affixList.append(wd[-4:-1])
        affixList.append(wd[1:3])
        affixList.append(wd[-3:-1])

        fLine += (wd[2:4] + "\t")
        fLine += (wd[1:4] + "\t")
        fLine += (wd[-4:-2] + "\t")
        fLine += (wd[-4:-1] + "\t")
        fLine += (wd[1:3] + "\t")
        fLine += (wd[-3:-1] + "\t")
    elif len(wd) > 4:
        for i in range(4):
            affixList.append("0")
            fLine += ("0\t")

        affixList.append(wd[1:3])
        affixList.append(wd[-3:-1])
        fLine += (wd[1:3] + "\t")
        fLine += (wd[-3:-1] + "\t")
    else:
        for i in range(6):
            fLine += ("0\t")
            affixList.append("0")
    # length of the word as a feature
    fLine += (str(len(wd)) + "\t")
    affixList.append(str(len(wd)))
    return affixList


######################################################
def loadWiktionary(wiktFile):
    arbPhrHash={}
    while 1:
        line = wiktFile.readline()
        if line == "":
            break
        line = line.strip().replace('\u00A0',"")
        if line.find(":") > -1:
            continue
        [arbPhr,engPhr] = line.split(" --- ")
        # don't store them if more than 2 words
        if arbPhr.strip().count(" ") > 2:
            continue
        if not arbPhrHash.has_key(arbPhr):
            arbPhrHash[arbPhr] = 1
    return arbPhrHash
######################################################
def loadStopWords(stopWdFile):
    stopWdHash = {}
    while 1:
        line = stopWdFile.readline()
        if line == "":
            break
        stopWd = line.strip()
        stopWdHash[stopWd] = 1
    return stopWdHash
######################################################
def findCapFeat(bWd, gloss):
    if bWd.strip() == gloss.strip():
        return "low"

    if bWd.startswith("@@") and  (bWd[7].upper() == bWd[7]):
        return "CAP"
    glossList1 = gloss.split(";")
    glossList2 = gloss.split(',')
    if (len (glossList1) > 1) or (len(glossList2) > 1):
        return "low"
    if gloss[0].upper() == gloss[0]:
        return "CAP"
    return "low"


def changeAffixFeats(affixFeats, wd):
    
    
    return affixFeats
######################################################
def changeMadaFeats(madaLine):

    madaFeats = madaLine.split()
    #old one [madaCapt, pos, cas, asp, num, per, gen, sst, normForm, 
    # noMadaAnalysis, isLatinWord, baseDiffThanNorm]
    # new ones: wd ,pos,cas,asp,num,per,gen,stt,normword,noanalysis,gloss

    # in the old feature extraction (wikipedia artciles), glos feature 
    # was in the first mada feature, but in the projection setup it was 
    # the last mada feature.  so we need to move it from the last to the front

    gloss = madaFeats.pop()
    madaFeats.insert(1,gloss)


    captFeat = findCapFeat(madaFeats[0], gloss)
    madaFeats[1] = captFeat


    # for pos
    collapseList = ["adv", "pron", "verb"]
    for clpos in collapseList:
        if madaFeats[2].startswith(clpos):
            madaFeats[2] = clpos
            break
    # aspect
    if madaFeats[4] != "na":
        madaFeats[4] = "app"
    


    # if the word is Arabic (non LAT)
    if madaFeats[0].startswith("@@LAT@@"):
        madaFeats.append("0")
    else:
        madaFeats.append("1")


    # if the base is same as the normalized form
    if madaFeats[0] == madaFeats[9]:
        madaFeats.append("1")
    else:
        madaFeats.append("0")

    return madaFeats

######################################################
def getWikipediaNEListFeatures(wdList, ct,wikiCaptDict, wikiNoCaptDict, stopWdHash):
    wikiCaptFeats = ""
    prevPhr = ""
    nextPhr = ""
    threeWdPhr = ""
    nextWd = ""
    wd = wdList[ct]
    prevWd = wdList[ct-1]
    wikiFeats = []

    if ct < len(wdList)-1:
        nextWd = wdList[ct+1]
        if not stopWdHash.has_key(wd) and not stopWdHash.has_key(nextWd) and len(wd) > 1 and len(nextWd) > 1:
            nextPhr = wd.strip() + " " + nextWd.strip()

    if not stopWdHash.has_key(wd) and not stopWdHash.has_key(prevWd) and len(wd) > 1 and len(prevWd) > 1:
        prevPhr = prevWd.strip() + " " + wd.strip()
        if stopWdHash.has_key(nextWd) and len(nextWd) > 1:
            threeWdPhr = prevPhr + " " + nextWd 
    # word-level feature for Wikipedia capitalized title lexicon
    if len(wd) > 1 and not stopWdHash.has_key(wd) and wikiCaptDict.has_key(wd):
        wikiFeats.append("1")

    else:
        wikiFeats.append("0")


    # phr-level feature (next word included) for Wikipedia capitalized title lexicon
    if nextPhr != "" and wikiCaptDict.has_key(nextPhr):
        wikiFeats.append("1")
    else:
        wikiFeats.append("0")

    # phr-level feature (prev word included) for Wikipedia capitalized title lexicon
    if prevPhr != "" and wikiCaptDict.has_key(prevPhr):
        wikiFeats.append("1")
    else:
        wikiFeats.append("0")

    # word-level feature for Wikipedia non-capitalized title lexicon
    if len(wd) > 1 and not stopWdHash.has_key(wd) and wikiNoCaptDict.has_key(wd):
        wikiFeats.append("1")
    else:
        wikiFeats.append("0")
    # phr-level feature for Wikipedia noncapitalized title lexicon
    if nextPhr != "" and wikiNoCaptDict.has_key(nextPhr):
        wikiFeats.append("1")
    else:
        wikiFeats.append("0")

    # phr-level feature for Wikipedia capitalized title lexicon
    if prevPhr != "" and wikiNoCaptDict.has_key(prevPhr):
        wikiFeats.append("1")
    else:
        wikiFeats.append("0")
    return wikiFeats


#####################################################
def readWords(path):
    fle = codecs.open(path,encoding='utf-8')
    wdList = []
    while 1:
        lne = fle.readline()
        if lne == "":
            break
        if lne.strip() != "":
            # remove the non-breaking space
            lne = lne.strip().replace(u'\u00A0',"")
            lnList = lne.split()
            wdList.append(lnList[0])
        else:
            wdList.append(" ")
    fle.close()
    return wdList
######################################################
def prepareOutputFeatureLine(wd, affixFeats, madaFeats, wikiNEFeats, tag):
    outLine = wd
    for affixF in affixFeats:
        outLine += ("\t" + affixF)
    
    for madaF in madaFeats[1:]:
        outLine += ("\t" + madaF)
    
    for i in range(4):
        outLine += ("\twk"+str(i)) 

    for wikiNEF in wikiNEFeats:
        outLine += ("\t" + wikiNEF)
    outLine += ("\t"+tag)
    return outLine

#######################################################
############### Main
######################################################
    

wdList = readWords(sys.argv[1])
taggedFile = codecs.open(sys.argv[1],encoding='utf-8')
madaFeatFile = codecs.open(sys.argv[2], encoding='utf-8')
# skip the first line of the mada features which has the heading
madaFeatFile.readline()
outFile = codecs.open(sys.argv[1]+".nerFeats",encoding='utf-8',mode='w')
counter = 0


wikiCaptFile = codecs.open(sys.argv[3],encoding='utf-8')
wikiNonCaptFile = codecs.open(sys.argv[4],encoding='utf-8')
stopWdFile = codecs.open(sys.argv[5],encoding='utf-8')
captHash = loadWiktionary(wikiCaptFile)
nonCaptHash = loadWiktionary(wikiNonCaptFile)
prevWd = wd = ""

stopWdHash = loadStopWords(stopWdFile)

# keep track of line numbers
ct = 0
perSentCounter = -1
print "done with loading"
while 1:
    tLine =  taggedFile.readline()
    mLine = madaFeatFile.readline().strip()
    if not tLine: break
    if len(tLine) < 4:
        outFile.write("\n")
        continue
    madaFeats = changeMadaFeats(mLine)
    [wd,tag] = tLine.strip().split()
    # collect: affix features, Wikipedia title features from lexicon
    affixFeats = getAffixFeats(wd)
    wikiNEFeats = getWikipediaNEListFeatures(wdList, ct,captHash, nonCaptHash, stopWdHash)
    outLine = prepareOutputFeatureLine(wd, affixFeats, madaFeats, wikiNEFeats, tag)
    outFile.write(outLine.strip() + "\n")
    ct += 1




######################################################################
"""
def convertOutline(line):
    lineList = line.split()
    newLine = ""
    for i in range(15):
        newLine = newLine + "\t" + lineList[i]
        # exlcude the first mada feature which is the word itself

    # go all the way till the "YES/NO feature for no-mada analysis
    for i in range(16,25):
        newLine = newLine + "\t" + lineList[i]
    # ignore the rest of mada features including the base!=lema and LAT
    
    # for all the wikipedia features
    for i in range(6):
        newLine = newLine + "\t^^"

    newLine += "\t" + lineList[28]
    newLine += "\t^^"
    newLine += "\t^^"
    newLine += "\t" + lineList[29]
    newLine += "\t^^"
    newLine += "\t^^"
    newLine  += "\t" 
    return newLine
"""
