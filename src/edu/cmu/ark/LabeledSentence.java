/*  
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
*/
package edu.cmu.ark;

import java.util.*;

public class LabeledSentence {

	public LabeledSentence(){
		tokens = new ArrayList<String>();
		trueLabels = new ArrayList<String>();
		predictions = new ArrayList<String>();
		
		trainDataID = "";
		articleID = "";
		diff = 0;
	}
	
	final String DEFAULT_PREDICTION = "";
	
	public String getArticleID() {
		return articleID;
	}

	public void setArticleID(String articleID) {
		this.articleID = articleID;
	}

	public void setDiff(double d){

		this.diff = d;

	}
	public String toString(){
		return this.taggedString();
	}

	private List<String> tokens; //words and punctuation
	private List<String> trueLabels; //true labels (if available from training/test data)
	private List<String> predictions; //predictions made by the system
	private List<String>[] features;
	private String articleID;
	private String trainDataID;
	private double diff; //the difference between the max score for tagging and second max score for tagging
	
	public String taggedString(){
		return taggedString(true);
	}


	/**
	 * creates 3-column format output
	 * 
	 * @param usePredictionsRatherThanGold
	 * @return
	 */
	public String taggedString(boolean usePredictionsRatherThanGold){
		String tok;
		String label;
		String res = "";
		String pos = "";
		for(int i=0; i<tokens.size(); i++){
			tok = tokens.get(i);
			//pos = posLabels.get(i);
			if(usePredictionsRatherThanGold){
				label = predictions.get(i);
				String tlabel = trueLabels.get(i);
				res += tok +"\t"+tlabel+"\t"+label+"\n";
			}else{
				label = trueLabels.get(i);
				res += tok +"\t"+label+"\n";
			}

		}
		//res+="diff="+diff+"\n";
		return res;
	}


	public void addToken(String token, String wikiInf, String th, String[] affixes, String wt, String dia, String iil, String[] mfeats, String label) {
		tokens.add(token);
		trueLabels.add(label);
		predictions.add(DEFAULT_PREDICTION);
	}

	private void initBasicFeatures(int numFeats) {
		features = new List[numFeats];	// these are "basic features", i.e. sufficient information for extracting model features
		for(int i=0; i<numFeats; i++){
			features[i] = new ArrayList<String>();
		}
	}

	public void addToken(String token, String[] feats, String label) {
		tokens.add(token);

		trueLabels.add(label);

		predictions.add(DEFAULT_PREDICTION);

		if (features==null) initBasicFeatures(feats.length);
		
		for(int i=0; i<feats.length; i++){
			features[i].add(feats[i]);
		}

	}
	
	/** Used for binary format input, where string values are indexed in a member of ArabicFeatureExtractor. */
	public void addToken(int[] feats, String label) {
		String[] strings = ArabicFeatureExtractor.getInstance().getStringVocabulary();

		trueLabels.add(label);
		
		predictions.add(DEFAULT_PREDICTION);
		
		tokens.add(strings[feats[0]]);
		
		if (feats.length<2) throw new RuntimeException("No features found: label="+label+", feats="+Arrays.toString(feats));
		
		if (features==null) initBasicFeatures(feats.length-1);
		for(int i=1; i<feats.length; i++){
			features[i-1].add(strings[feats[i]]);
		}
	}
	

	public void addToken(String token, String[] affixes, String[] mfeats, String label) {
		tokens.add(token);

		trueLabels.add(label);

		predictions.add(DEFAULT_PREDICTION);
	}
	public void addToken(String token, String[] affixes, String[] mfeats, String tdi, String label) {
		tokens.add(token);

		trueLabels.add(label);

		predictions.add(DEFAULT_PREDICTION);

		trainDataID = tdi;
	}
	

	public List<String> getTokens() {
		return tokens;
	}

	public double getDiff(){
		return diff;
	}
	
	public List<String>[] getFeatures(){
		return features;
	}
	
	public List<String> getLabels() {
		return trueLabels;
	}

	public int length(){
		return tokens.size();
	}

	public List<String> getPredictions() {
		return predictions;
	}

	public boolean predictionsAreCorrect() {
		for(int i=0;i<trueLabels.size();i++){
			if(!predictions.get(i).equals(trueLabels.get(i))){
				return false;
			}
		}
		return true;
	}
	
	public void setPredictions(List<String> predictions) {
		this.predictions = predictions;
	}
}
