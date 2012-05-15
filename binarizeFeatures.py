#!/usr/bin/env python2.7
'''
Given a feature file, indexes the string values of tab-separated columns
and produces an equivalent feature file in binary format (.bin), as well as 
two vocabulary files. The last column of each line is treated specially 
as the label; values in the last column are indexed in a separate vocabulary 
a stored in a label vocabulary file (.lvocab). The remaining values are 
indexed in the feature value string vocabulary without regard to column position; 
these are listed in an .svocab file. Input lines may therefore contain different 
numbers of columns.

In the output, 20 is added to each index and 10 is interpreted as a line break. 
The binary format is compatible with Java's java.io.DataInput.readInt() method.

@author: Nathan Schneider (nschneid)
@since: 2012-04-19
'''
from __future__ import print_function, division
import os, sys, re, codecs, fileinput
from collections import Counter, defaultdict

                                                                  
'''
flds = slice(None)
if sys.argv[1]=='-f':
    fnum = sys.argv[2]
    if '-' in fnum: # Unix-style field range (1-based)
        start = int(fnum.split('-')[0])-1
        stop = int(fnum.split('-')[1])
        flds = slice(start,stop)
    del sys.argv[1]
    del sys.argv[1]
'''

# source: http://mail.python.org/pipermail/python-list/2002-January/740638.html
def unsigned_rshift(num, dist):
	'''The >>> operator in Java.'''
	if dist == 0:
		v = num
	else:
		v = ( (num >> dist) & (0x7fffffff >> (dist-1) ) )
	assert (num>>dist)==v	# do we actually encounter cases where this matters? (i think it only matters for negative numbers, which have a sign bit of 1)
	return v

def jwriteint(v):
	'''
	Encodes an integer as a Java-readable byte string.
	See http://docs.oracle.com/javase/1.5.0/docs/api/java/io/DataOutput.html#writeInt%28int%29
	'''
	s = b''.join((
		chr(0xff & unsigned_rshift(v, 24)),
		chr(0xff & unsigned_rshift(v, 16)),
		chr(0xff & unsigned_rshift(v, 8)),
		chr(0xff & unsigned_rshift(v, 0))))
	#assert jreadint(s)==v
	return s
	
def jreadint(s):
	a,b,c,d = [ord(ch) for ch in s]
	return ((a << 24) + (b << 16) + (c << 8) + (d << 0))

assert len(sys.argv)==2

indices = {}
labels = {}

outFP = sys.argv[1]+'.bin'
svocabFP = sys.argv[1]+'.svocab'	# string vocabulary (for feature values)
lvocabFP = sys.argv[1]+'.lvocab'	# label vocabulary
nvecs = 0
nblanks = 0
import io
with io.FileIO(outFP, 'wb') as outF, open(svocabFP, 'w') as svocabF, open(lvocabFP, 'w') as lvocabF:
	for ln in fileinput.input():
		if not ln.strip():
			outF.write(jwriteint(10))
                        if nblanks%10000==0:
                            print('blank lines encountered: ', nblanks, file=sys.stderr)
                        nblanks += 1
			continue
		parts = ln[:-1].split('\t')
		
		# features
		for i,val in enumerate(parts[:-1]):
			#name = '{i}={val}'.format(i=i+1, val=val)
			name = val	# does not consider column position
			n = len(indices)
			j = indices.setdefault(name, n)
			if j==n:
				svocabF.write(name+'\n')
			outF.write(jwriteint(j+20))  # write the index of the feature +20 in binary format
		
		# label
		lbl = parts[-1]
		nlbls = len(labels)
		l = labels.setdefault(lbl, nlbls)
		if l==nlbls:
			lvocabF.write(lbl+'\n')
		outF.write(jwriteint(l+20))
		outF.write(jwriteint(10)) # 10 functions as a line break
		nvecs += 1
		
		#if nvecs>20:
		#	assert False

print('Wrote {} vectors to {}'.format(nvecs, outFP), file=sys.stderr)
print('Wrote {} feature value strings to {}'.format(len(indices), svocabFP), file=sys.stderr)
print('Wrote {} labels to {}'.format(len(labels), lvocabFP), file=sys.stderr)
