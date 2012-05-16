#!/usr/bin/env bash

TARGET=arabic-tagger.jar
set -eux

rm -rf bin
mkdir -p bin

javac -cp .:lib/JSAP-2.1.jar -d bin src/edu/cmu/ark/util/LineChunkReader.java src/edu/cmu/ark/DiscriminativeTagger.java src/edu/cmu/ark/LabeledSentence.java src/edu/cmu/ark/ArabicFeatureExtractor.java

cd bin
echo "Main-Class: edu.cmu.ark.DiscriminativeTagger
Class-Path: lib/JSAP-2.1.jar
" > manifest.txt
jar cmf manifest.txt $TARGET *
cd ..

mv bin/$TARGET .
