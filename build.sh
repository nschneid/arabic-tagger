#!/usr/bin/env bash

TARGET=arabic-tagger.jar
set -eux

rm -rf bin
mkdir -p bin

javac -cp .:lib/JSAP-2.1.jar -d bin src/edu/cmu/ark/util/LineChunkReader.java src/edu/cmu/ark/DiscriminativeTagger.java src/edu/cmu/ark/LabeledSentence.java src/edu/cmu/ark/ArabicFeatureExtractor.java

cd bin
jar cf $TARGET *
cd ..

mv bin/$TARGET .