package edu.cmu.ark;

import java.util.*;
import java.util.zip.GZIPInputStream;
import java.io.*;

import com.martiansoftware.jsap.JSAPResult;


/**
 * Loads features from a tab-delimited feature file for Arabic data.
 * At least 36 features are expected; some have default values designated for 
 * which no feature will be extracted. Beyond the first 36, the value "0" 
 * is the default.
 */
public class ArabicFeatureExtractor {

	private static ArabicFeatureExtractor instance;

	private String[] strings = null;

	private boolean useClusterFeatures;
	private boolean useBigramFeatures;
	private boolean useMorphCache;
	private boolean usePrevLabel;

	Set<Integer> excludeFeatNums = new HashSet<Integer>();

	private ArabicFeatureExtractor(JSAPResult opts){
		useClusterFeatures = opts.getBoolean("useClusterFeatures");

		useBigramFeatures = opts.getBoolean("useBigramFeatures");

		useMorphCache = opts.getBoolean("useMorphCache");
		
		usePrevLabel = opts.getBoolean("usePrevLabel");
		
		// formerly: "useFeatureNumber"
		String excludeFeatures = opts.getString("excludeFeatures");
		String[] excludeFeatureNums = excludeFeatures.split(","); 

		if(excludeFeatureNums[0].equals("")){
			return;
		}
		for(int i=0; i<excludeFeatureNums.length; i++){
			excludeFeatNums.add(Integer.parseInt(excludeFeatureNums[i])-1);
		}
	}

	public static ArabicFeatureExtractor getInstance(){
		if(instance == null){
			instance = new ArabicFeatureExtractor(DiscriminativeTagger._opts);
		}
		return instance;
	}


	/**
	 * Extracts a map of feature names to values for a particular token in a sentence.
	 * Rather than returning feature strings, relevant feature name indices 
	 * are stored in relevantFeatureIndices[0] and the corresponding values are 
	 * returned in a parallel array.
	 * 
	 * For efficiency, decoding will handle zero-order features separately 
	 * from first-order features. This method only returns the former; see
	 * {@link #extractFirstOrderFeatureValues(LabeledSentence,int,Map<String,Integer>,int[][],boolean,boolean)}
	 * for the latter.
	 *
	 * @param sent the labeled sentence object to extract features from
	 * @param j index of the word in the sentence to extract features for
	 * @param featureIndexes Mapping from (lifted) feature names to indices
	 * @param relevantFeatureIndices The zeroth element of the outer array will be modified to contain 
	 * an inner array of feature offsets, parallel to the returned double[] of feature values
	 * @param usePredictedLabels Status of label (gold or predicted) to use for first-order features
	 * @param addNewFeatures Whether to include (grounded) features that do not already have an index
	 * @return
	 */
	public double[] extractZeroOrderFeatureValues(LabeledSentence sent, int j, Map<String,Integer> featureIndexes, 
		int[][] relevantFeatureIndices, boolean usePredictedLabels, boolean addNewFeatures) {
		
		// feature names -> values
		Map<String, Double> featureMap = new HashMap<String, Double>();
		
		// numbered features from feature file input
		List<String>[] features = sent.getFeatures();
		
		
		featureMap.put("currentTok="+sent.getTokens().get(j),1.0);
		
		if(j>0) {
			featureMap.put("previousTok="+sent.getTokens().get(j-1),1.0);
			if(j>1)
				featureMap.put("previous2Tok="+sent.getTokens().get(j-2),1.0);
		}
		
		
		
		// Numbered features for the current token
		
		addFeat(0, features, j, featureMap);
		addFeat(1, features, j, featureMap);
		if(features[2].get(j).length()==2) addFeat(2, features, j, featureMap);
		if(features[3].get(j).length()==2) addFeat(3, features, j, featureMap);
		if(features[4].get(j).length()==3) addFeat(4, features, j, featureMap);
		if(features[5].get(j).length()==3) addFeat(5, features, j, featureMap);

		/*
		// It looks like there was code intending to avoid adding features with "default" values, 
		// but this was being superseded by an 'else' clause
		addFeatUnlessEq(13, features, j, featureMap, "low");
		addFeatUnlessEq(21, features, j, featureMap, "UNK");
		addFeatUnlessEq(22, features, j, featureMap, "YES");
		addFeatUnlessEq(25, features, j, featureMap, "txt");
		addFeatUnlessEq(26, features, j, featureMap, "NAN");
		addFeatUnlessEq(28, features, j, featureMap, "nd");
		*/
		
		for (int f=6; f<12; f++) addFeatUnlessEq(f, features, j, featureMap, "0");
		for (int f=12; f<15; f++) addFeat(f, features, j, featureMap);
		for (int f=15; f<21; f++) addFeatUnlessEq(f, features, j, featureMap, "na");
		addFeat(21, features, j, featureMap);
		addFeatUnlessEq(22, features, j, featureMap, "0");
		addFeatUnlessEq(23, features, j, featureMap, "0");
		for (int f=24; f<29; f++) addFeat(f, features, j, featureMap);
		for (int f=29; f<35; f++) addFeatUnlessEq(f, features, j, featureMap, "0");
		for (int f=35; f<features.length; f++) addFeatUnlessEq(f, features, j, featureMap, "0");	// nschneid: added (allows additional feature templates)
		
		//bias
		featureMap.put("bias",1.0);

		// MADA features from the previous token
		if(j>0){
			for(int i=13; i<25; i++){
				if(excludeFeatNums.contains(i))
					continue;
				featureMap.put("prevmadafeat "+i+"="+features[i].get(j-1), 1.0);
			}
		}
		
		if (!addNewFeatures) {	// remove any new features (not already in the vocabulary)
			for (Iterator<Map.Entry<String, Double>> it = featureMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Double> entry = it.next();
				if (!featureIndexes.containsKey(entry.getKey())) {
					it.remove();
				}
			}
		}

		// convert feature names to offsets
		relevantFeatureIndices[0] = new int[featureMap.size()];
		double[] featureVals = new double[featureMap.size()];
		int q=0;
		for (Map.Entry<String,Double> item : featureMap.entrySet()) {
			if (!featureIndexes.containsKey(item.getKey()))
				featureIndexes.put(item.getKey(), featureIndexes.size());
			relevantFeatureIndices[0][q] = featureIndexes.get(item.getKey());
			featureVals[q] = item.getValue();
			q++;
		}

		return featureVals;
	}
	
	public int[] extractFirstOrderFeatureValues(LabeledSentence sent, int j, Map<String,Integer> featureIndexes, 
		boolean usePredictedLabels, boolean addNewFeatures) {
		// previous label feature (first-order); assuming just one of these for any given token
		if(hasFirstOrderFeatures() && j>0){
			String prevLabel = (usePredictedLabels) ? sent.getPredictions().get(j-1) : sent.getLabels().get(j-1);
			final String bigramFeature = "prevLabel="+prevLabel;
			if (addNewFeatures || featureIndexes.containsKey(bigramFeature)) {
				// fire a label bigram feature
				if (!featureIndexes.containsKey(bigramFeature))
					featureIndexes.put(bigramFeature, featureIndexes.size());
				return new int[]{featureIndexes.get(bigramFeature)};
			}
			return new int[]{};
		}
		return null;
	}
	
	private boolean addFeat(int featnum, List<String>[] features, int tkn, Map<String, Double> featMap) {
		if (excludeFeatNums.contains(featnum)) return false;
		featMap.put("feat "+featnum+"="+features[featnum].get(tkn), 1.0);
		return true;
	}
	private boolean addFeatUnlessEq(int featnum, List<String>[] features, int tkn, Map<String, Double> featMap, String comparisonValue) {
		if (excludeFeatNums.contains(featnum)) return false;
		if (features[featnum].get(tkn).equals(comparisonValue)) return false;
		return addFeat(featnum, features, tkn, featMap);
	}

	public boolean hasFirstOrderFeatures() {
		return usePrevLabel;
	}

	public void setStringVocabulary(String[] strings) {
		this.strings = strings;
	}
	public String[] getStringVocabulary() {
		return this.strings;
	}


}
