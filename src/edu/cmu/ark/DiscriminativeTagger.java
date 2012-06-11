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


import java.text.NumberFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.*;

//import edu.stanford.nlp.ling.HasWord;
//import edu.stanford.nlp.process.DocumentPreprocessor;
//import edu.stanford.nlp.tagger.maxent.*;


// command line option parsing
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.Parameter;
import com.martiansoftware.jsap.Switch;
import com.martiansoftware.jsap.defaultsources.PropertyDefaultSource;


import edu.cmu.ark.util.LineChunkReader;

public class DiscriminativeTagger implements Serializable{
	
	/** Reads individual sentences from a feature file and represents each as LabeledSentence. 
	 *  Gives the option of querying the sentences in a randomly shuffled order.
	 *  The feature file may be in a text format (one token per line, with feature values in 
	 *  tab-delimited columns) or in binary format, with integers >=20 corresponding to 
	 *  grounded feature offsets and the value 10 serving as a break between tokens.
	 *  Multiple consecutive token delimiter characters indicate a sequence break.
	 */
	static class FeatureFileReader implements Iterator<LabeledSentence>, Iterable<LabeledSentence> {
		LineChunkReader _seqrdr;
		Iterator<List> _seqiter;
		List<String> _lbls;
		boolean _binarized;
		boolean _allowunk;
		
		public FeatureFileReader(File file, List<String> labelTypes, boolean binarized) throws IOException {
			this(file,labelTypes,binarized,false);
		}
		
		public FeatureFileReader(File file, List<String> labelTypes, boolean binarized, boolean allowUnknownLabelTypes) throws IOException {
			_seqrdr = new LineChunkReader(file,binarized);
			_seqiter = _seqrdr.iterator();
			if (labelTypes.size()<2)
				throw new RuntimeException("Need at least two label types: "+labelTypes);
			_lbls = labelTypes;
			_binarized = binarized;
			_allowunk = allowUnknownLabelTypes;
		}
		
		/*public void shuffle() throws IOException {
			_seqrdr.shuffle();
		}*/
		
		public void close() {
			_seqrdr.close();
		}
		
		public boolean hasNext() {
			return _seqiter.hasNext();
		}
		
		public LabeledSentence next() {
			if (!hasNext()) { System.err.println("returning null"); return null; }
			
			LabeledSentence sent = new LabeledSentence();
			List chunk = _seqiter.next();
			for (Object oln : chunk) {
			
				if (_binarized) {
					int[] ln = (int[])oln;
					sent.addToken(Arrays.copyOfRange(ln, 0, ln.length-1), _lbls.get(ln[ln.length-1]));
				}
				else {
					String ln = (String)oln;
					String[] parts = ln.split("\\t");
					if (parts.length<37) {
						throw new RuntimeException("Feature file line ("+ln.length()+" chars) has too few ("+parts.length+") columns (is the label missing?): "+ln);
					}
					String label = parts[parts.length-1];	// nschneid: was parts[36]; generalized to support additional feature templates
					try {
						label = _lbls.get(_lbls.indexOf(label));	// use canonical string for the label
					} catch (ArrayIndexOutOfBoundsException ex) {
						System.err.println("Not present among "+_lbls.size()+" known label types: "+label);
						if (!_allowunk) {
							ex.printStackTrace();
							System.exit(1);
						}
					}
//					String affs[] = new String[6];
//					String madafeats[] = new String[11];
//					//int j = 1; 

					// features - excludes the token itself (beginning of line) and label (end of line)
					String features[] = new String[parts.length-2];	// nschneid: was String[35]; generalized to support additional features
					for(int i=1; i<parts.length-1; i++){
						features[i-1] = parts[i];
					}
					sent.addToken(parts[0], features, label);
				}
				
				//System.out.println("Sentence length: "+ sent.length());
				/*				if(parts.length > 3 && !parts[3].equals("")){
		  sent.setArticleID(parts[3]);
		  }*/
			}
			
			if(sent.length()>0) return sent;
			System.err.println("returning null 2");
			return null;
		}
		
		public Iterator<LabeledSentence> iterator() {
			_seqiter = _seqrdr.iterator();	// iteration always starts from scratch (allows iterating over the data multiple times)
			return this;
		}
		
		public void remove() { System.err.println("FeatureFileReader.remove() not supported"); }
	}
	
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 7096385301991299782L;
	public DiscriminativeTagger(){
		featureIndexes = new HashMap<String,Integer>();
		trainingData = null;
		labels = new ArrayList<String>();
		rgen = new Random(1234567);
	}

	
	/**
	 * Load a list of the possible labels.  This must be done before training
	 * so that the feature vector has the appropriate dimensions 
	 * 
	 * @param labelFile
	 * @return
	 */
	public static List<String> loadLabelList(String labelFile){
		List<String> res = new ArrayList<String>();	// TODO: use LinkedList instead since we are simply 1) iterating over and 2) shuffling?
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(labelFile)));
			String buf;
			while((buf = br.readLine())!= null){
				res.add(buf);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return res;
	}


	/**
	 * Load the tagged data from the specified feature file.
	 * @param path Path to the feature file
	 * @param labels Full set of tags that may be seen in the data
	 * @param binaryFeats Whether the feature file is in binary format
	 * @param allowUnknownLabels Whether to terminate the program upon encountering 
	 * instances with labels not in the set of known label types. If true, a 
	 * warning will simply be displayed whenever an unknown label is encountered.
	 * @return List of tagged sentences
	 */
	public static List<LabeledSentence> loadData(String path, List<String> labels, boolean binaryFeats, boolean allowUnknownLabels){
		List<LabeledSentence> sents = new ArrayList<LabeledSentence>();
		
		try {
			System.err.print("loading all data into memory from "+path);
			int nSent = 0;
			for (LabeledSentence sent : new FeatureFileReader(new File(path), labels, binaryFeats, allowUnknownLabels)) {
				sents.add(sent);
 				if (nSent%1000==0) System.err.print(".");
 				nSent++;
			}
			System.err.println(" done");
			
		} catch (IOException e) {
			e.printStackTrace();
		}

		return sents;
	}


	/**
	 * remove labels for adjectives and adverbs, which the SST does not address
	 * because they are lumped together in wordnet
	 * 
	 * @param label
	 * @param labels
	 * @return
	 */
	public static String removeExtraLabels(String label, List<String> labels) {
		/*if(label.contains("-adj.") || label.contains("-adv.") || label.endsWith(".other")){
	  return "0";
	  }*/
		if(!labels.contains(label)){
			return "O";
		}
		return label;
	}



	private static FlaggedOption flag(String name, String help) {
		return (FlaggedOption)new FlaggedOption(name).setLongFlag(name).setHelp(help);
	}
	private static Switch boolflag(String name, String help) {
		return (Switch)new Switch(name).setLongFlag(name).setHelp(help);
	}


	/**
	 * @param args
	 */
	public static void main(String[] args) {
		JSAP optparser = null;
		try {
			optparser = new com.martiansoftware.jsap.SimpleJSAP(
				"DiscriminativeTagger", 
				"Learn or predict from a discriminative tagging model",
				new Parameter[]{
					flag("train", "Path to training data feature file"),
					boolflag("disk", "Load instances from the feature file in each pass through the training data, rather than keeping the full training data in memory"),
					flag("iters", "Number of passes through the training data").setStringParser(JSAP.INTEGER_PARSER).setDefault("1"),
					flag("test", "Path to test data for a CoNLL-style evaluation; scores will be printed to stderr (following training, if applicable)"),
					boolflag("debug", "Whether to save the list of feature names (.features file) prior to training, as well as an intermediate model (serialized model file and text file with feature weights) after each iteration of training"),
					flag("labels", "List of possible labels, one label per line"),
					flag("save", "Save path for serialized model file (training only). Associated output files (with --debug) will add a suffix to this path."),
					flag("load", "Path to serialized model file (decoding only)"),
					flag("properties", "Properties file with option defaults").setDefault("tagger.properties"),
					//boolflag("mira"),
					boolflag("weights", "Write feature weights to stdout after training"),
					flag("test-predict", "Path to feature file on which to make predictions (following training, if applicable); predictions will be written to stdout. (Will be ignored if --test is supplied.)"),
					
					// formerly only allowed in properties file
					flag("useBIO", "Constrain label bigrams in decoding such that the 'O' label is never followed by a label beginning with 'I'").setStringParser(JSAP.BOOLEAN_PARSER).setDefault("true"),
					flag("useCostAug", "Value of cost penalty for errors against recall (for recall-oriented learning)").setStringParser(JSAP.DOUBLE_PARSER).setDefault("0"),
					flag("usePrevLabel", "Include a first-order (label bigram) feature").setStringParser(JSAP.BOOLEAN_PARSER).setDefault("true"),
					
					// formerly: "useFeatureNumber"
					flag("excludeFeatures","Comma-separated list of (0-based) column numbers to ignore when reading feature files. (Do not specify column 0; use --no-lex instead.)").setDefault(""),
					
					boolflag("no-lex", "Don't include features for current and context token strings"),
					boolflag("no-averaging", "Don't use averaging in perceptron training")
				});
		} catch (com.martiansoftware.jsap.JSAPException ex) {
			ex.printStackTrace();
			System.exit(1);
		}
		
		optparser.registerDefaultSource(new PropertyDefaultSource(optparser.parse(args).getString("properties"), true));
		JSAPResult opts = optparser.parse(args);
	
		String trainFile = opts.getString("train");
		String testFile = opts.getString("test");
		String labelFile = opts.getString("labels");
		boolean loadTrainInMemory = !opts.getBoolean("disk");
		final boolean binaryFeats = false;
		int maxIters = opts.getInt("iters");
		boolean developmentMode = opts.getBoolean("debug");
		String saveFile = opts.getString("save");
		String loadFile = opts.getString("load");
		boolean perceptron = true || !opts.getBoolean("mira");
		boolean printWeights = opts.getBoolean("weights");
		String testPredictFile = opts.getString("test-predict");
		
		_opts = opts;	// static class variable


		if(trainFile == null && loadFile == null){
			System.err.println("Missing argument: --train or --load");
			System.exit(0);
		}
		if(labelFile == null && loadFile == null){
			System.err.println("Missing argument: --labels");
			System.exit(0);
		}

		DiscriminativeTagger t;
		
		if(loadFile != null){
			System.err.print("loading model from "+loadFile+"...");
			t = DiscriminativeTagger.loadModel(loadFile);
			// override options used during training that may be different for prediction
			t.setBinaryFeats(binaryFeats);
			t.setDevelopmentMode(developmentMode);
			System.err.println("done.");
		}else{
			System.err.println("training model from "+trainFile+"...");
			t = new DiscriminativeTagger();
			t.setBinaryFeats(binaryFeats);
			t.setDevelopmentMode(developmentMode);
			t.setPerceptron(perceptron);
			t.setSavePrefix(saveFile);
			List<String> labels = loadLabelList(labelFile);
			t.setLabels(labels);

			if (loadTrainInMemory) {
				List<LabeledSentence> data = loadData(trainFile,labels,binaryFeats,false);
				t.setTrainingData(data);
			}
			else {
				try {
					FeatureFileReader datardr = new FeatureFileReader(new File(trainFile), labels, binaryFeats);
					t.setTrainingData(datardr);
				} catch (IOException ex) {
					ex.printStackTrace();
					System.exit(1);
				}
			}
		}
		
		if(testFile != null){
			List<LabeledSentence> data = loadData(testFile,t.getLabels(),binaryFeats,true);
			t.setTestData(data);
		}

		if(loadFile == null){
			t.setMaxIters(maxIters);
			t.train();
		}

		if(testFile != null){
			t.test();
		}else if(printWeights){
			t.printWeights(System.out);
		}else if(testPredictFile != null){
			//data = loadData(testPredictFile, t.getLabels(),true);
			//t.printPredictions(data, t.getWeights());
			// nschneid: the above didn't scale to large files; instead:
			t.printPredictions(testPredictFile, t.getLabels(), t.getWeights());
		}else{
// 			t.tagStandardInput();
		}
	}

	public void printFeatures(PrintStream out){
		out.println(featureIndexes.size()+" lifted features x "+labels.size()+" labels = "+featureIndexes.size()*labels.size()+" grounded features");
		out.println("labels: "+labels+"\n");
		List<String> fnames = new ArrayList<String>();
		fnames.addAll(featureIndexes.keySet());
		Collections.sort(fnames);
		for(String fname: fnames){
			out.println(fname);
		}
	}

	public void printWeights(PrintStream out){
		printWeights(out, finalWeights);
	}
	
	public void printWeights(PrintStream out, double[] weights){
		List<String> fnames = new ArrayList<String>();
		fnames.addAll(featureIndexes.keySet());
		Collections.sort(fnames);
out.println(fnames.size() + " " + labels.size() + " " + weights.length + " " +weights[0]);
int nNonzero = 0;
for (double w : weights) {
	if (w!=0.0) nNonzero++;
}
if (nNonzero==0) throw new RuntimeException("All weights are 0.");
		for(String fname: fnames){
			int findex = featureIndexes.get(fname);
			for(int i=0; i<labels.size();i++){
				String label = labels.get(i);
				double value = weights[getGroundedFeatureIndex(findex,i)];
				if(value != 0.0){
					out.println(label+"\t"+fname+"\t"+value);
				}
			}
		}
	}


	/**
	 * method to take input on STDIN, tag it using the stanford POS tagger,
	 * make predictions, and then print it as output
	 * 
	 * 
	 */
// 	protected void tagStandardInput(){
// 		try {
// 			new MaxentTagger(properties.getProperty("posTaggerModel"));
// 		
// 		
// 			BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
// 	
// 			// nschneid: modified to treat each line as a separate document (assumes line breaks are always sentence breaks); this allows the tagger to be run on large data
// 			String ln;
// 			while((ln=br.readLine())!=null){
// 			    if (ln.trim().length()==0)
// 				continue;
// 			
// 			    List<String> sentences = getSentences(ln); // sentence-split the line
// 
// 			    // tag the sentence(s) on this line
// 			    for(String s: sentences){
// 				LabeledSentence input = new LabeledSentence();
// 				String tagged = MaxentTagger.tagString(s);
// 				String [] taggedTokens = tagged.split("\\s");
// 				int idx;
// 				for(int i=0;i<taggedTokens.length;i++){
// 				    idx = taggedTokens[i].lastIndexOf('_');
// 				    String token = taggedTokens[i].substring(0, idx);
// 				    String pos = taggedTokens[i].substring(idx+1);
// 				    input.addToken(token, ArabicFeatureExtractorgetInstance().getStem(token, pos), pos, "0");
// 				}
// 				
// 				findBestLabelSequenceViterbi(input, finalWeights);
// 				System.out.println(input.taggedString());
// 			    }
// 			}
// 			
// 		} catch (Exception e) {
// 			e.printStackTrace();
// 		}
// 	}
	
	
	public List<String> getLabels() {
		return labels;
	}




	private void setLabels(List<String> labels){
		this.labels = labels;
	}




	private void setDevelopmentMode(boolean developmentMode) {
		this.developmentMode = developmentMode;
	}
	private void setBinaryFeats(boolean binarized) {
		this.binaryFeats = binarized;
	}


	/**
	 * helper function for making predictions about raw text
	 * 
	 * @param document
	 * @return
	 */
	//	public static List<String> getSentences(String document) {
	//		DocumentPreprocessor dp = new DocumentPreprocessor(false);
	//		List<String> res = new ArrayList<String>();
	//		String sentence;
	//		StringReader reader = new StringReader(document);
	//		
	//		List<List<? extends HasWord>> docs = new ArrayList<List<? extends HasWord>>();
	//		Iterator<List<? extends HasWord>> iter1 ;
	//		Iterator<? extends HasWord> iter2;
	//		
	//		try{
	//			docs = dp.getSentencesFromText(reader);
	//		}catch(Exception e){
	//			e.printStackTrace();
	//		}
	//		
	//		iter1 = docs.iterator();
	//		while(iter1.hasNext()){
	//			iter2 = iter1.next().iterator();
	//			sentence = "";
	//			while(iter2.hasNext()){
	//				String tmp = iter2.next().word().toString();
	//				sentence += tmp;
	//				if(iter2.hasNext()){
	//					sentence += " ";
	//				}
	//			}
	//			res.add(sentence);
	//		}
	//		
	//		return res;
	//	}


	/**
	 * serialize model, clearing out unneeded data first 
	 * (and then resetting it so it can be used in subsequent iterations if necessary)
	 * 
	 * @param savePath
	 */
	private void saveModel(String savePath) {
		Iterable<LabeledSentence> tmpTrainingData = trainingData;
		List<LabeledSentence> tmpTestData = testData;
		String tmpSavePrefix = savePrefix;
		try {
			ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(savePath)));
			trainingData = null;
			testData = null;
			savePrefix = null;
			out.writeObject(this);
			out.flush();
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		trainingData = tmpTrainingData;
		testData = tmpTestData;
		savePrefix = tmpSavePrefix;
	}


	/**
	 * 
	 * load a serialized model
	 * 
	 * @param loadPath
	 * @return
	 */
	public static DiscriminativeTagger loadModel(String loadPath){
		DiscriminativeTagger res = null;
		try {
			ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(loadPath)));
			res = (DiscriminativeTagger) in.readObject();
		} catch (Exception e) {
			e.printStackTrace();
		}

		return res;
	}


	public void setTrainingData(Iterable<LabeledSentence> trainingData) {
		this.trainingData = trainingData;	
	}


	/**
	 * train the model using the averaged perceptron 
	 * (or perhaps MIRA in the future, but that doesn't currently work)
	 * See Collins paper on Discriminative HMMs. 
	 * 
	 */
	public void train(){
		boolean averaging = !_opts.getBoolean("no-averaging");
		
		if(trainingData == null){
			System.err.println("training data not set.");
			return;
		}
		if(perceptron) System.err.println("training with the perceptron.");
		else System.err.println("training with 1-best MIRA.");

		createDPTables();
		
		try {
			trainingData = createFeatures();
		} catch (IOException ex) {
			ex.printStackTrace();
			System.exit(1);
		}
		
		System.err.println("training data type: "+trainingData.getClass().getName());

		// finalWeights will contain a running average of the currentWeights vectors at all timesteps
		double[] currentWeights = new double[finalWeights.length];

		long numWordsProcessed = 0;
		long numWordsIncorrect=0;
		long totalInstancesProcessed = 0;
		
		//long trainingDataSize = 0;
		
		
		if(developmentMode && savePrefix != null) {	// print features before training
			try {
				PrintStream out = new PrintStream(new BufferedOutputStream(new FileOutputStream(savePrefix+".features")));
				printFeatures(out);
				out.close();
			} catch (FileNotFoundException ex) {
				ex.printStackTrace();
			}
		}

		for(int numIters=0;numIters<maxIters;numIters++){
			System.err.println("iter="+numIters);
			
			// shuffle the training data if not reading it incrementally
			if (trainingData instanceof List)
				Collections.shuffle((List)trainingData,rgen);
			
			int nWeightUpdates = 0;
			for(LabeledSentence sent : trainingData){
				if(perceptron){
					findBestLabelSequenceViterbi(sent, currentWeights);
					nWeightUpdates += perceptronUpdate(sent, currentWeights, totalInstancesProcessed, finalWeights);
					// will update currentWeights as well as running average in finalWeights
				}else{
					throw new RuntimeException("MIRA is not currently supported");
					/*findBestLabelSequenceViterbi(sent, intermediateWeights, true);
					MIRAUpdate(sent, intermediateWeights);*/
				}

				for(int j=0; j<sent.length(); j++){
					if(!sent.getLabels().get(j).equals(sent.getPredictions().get(j))){
						numWordsIncorrect++;
					}
				}
				numWordsProcessed+=sent.length();
				totalInstancesProcessed++;
				//System.out.println("size of weights:" + finalWeights.length);
/*				for(int f=0;f<finalWeights.length;f++){

					finalWeights[f] += intermediateWeights[f];
				}*/

				if(totalInstancesProcessed % 500 == 0){
					System.err.println("totalInstancesProcessed="+totalInstancesProcessed);
					System.err.println("pct. correct words in last 500 inst.:"+NumberFormat.getInstance().format((double)(numWordsProcessed-numWordsIncorrect)/numWordsProcessed));
					numWordsIncorrect=0; numWordsProcessed=0;
				}


			}
			
			//trainingDataSize = totalInstancesProcessed / (numIters+1);
			
			if(developmentMode){
				//double normalizer = ((double)numIters+1) * trainingDataSize;
				//multiplyByScalar(finalWeights, 1.0/normalizer);	// averaging
				test();
				if(savePrefix != null) {
					if (!averaging)
						finalWeights = currentWeights;
					
					saveModel(savePrefix+"."+numIters);
					try {
						PrintStream out = new PrintStream(new BufferedOutputStream(new FileOutputStream(savePrefix+"."+numIters+".weights")));
						printWeights(out, currentWeights);	// note: the serialized model, but not the printed model, has averaging
						out.close();
					} catch (FileNotFoundException ex) {
						ex.printStackTrace();
					}
				}
				//multiplyByScalar(finalWeights, normalizer);	// undo the averaging
			}
			
			System.err.println("weight updates this iteration: "+nWeightUpdates);
			if (nWeightUpdates==0) {
				System.err.println("converged! stopping training");
				break;
			}
		}

		if (!averaging)
			finalWeights = currentWeights;

		//average the weights for the "averaged" part of the averaged perceptron 
		//double normalizer = (double)maxIters * trainingDataSize;
		//multiplyByScalar(finalWeights, 1.0/normalizer);
		if(savePrefix != null) saveModel(savePrefix);
	}

	private void multiplyByScalar(double[] weights, double scalar) {
		for(int i=0;i<weights.length; i++){
			weights[i] *= scalar;
		}
	}

	private int getGroundedFeatureIndex(int liftedFeature, int label) {
		return liftedFeature + label*featureIndexes.size();
	}


	/**
	 * helper method for perceptron training.
	 * basically, update weights by adding the feature vector for the correct label
	 * and subtracting the feature vector for the predicted label  
	 * 
	 * this method breaks down the process so that the changes are made token by token 
	 * 
	 * @param sent
	 * @param currentWeights Latest value of the parameter vector
	 * @param timestep Number of previous updates that have been applied
	 * @param runningAverageWeights Average of the 'timestep' previous weight vectors
	 * @return Number of weights updated
	 */
	private int perceptronUpdate(LabeledSentence sent, double[] currentWeights, long timestep, double[] runningAverageWeights) {
		if(sent.predictionsAreCorrect()){
			return 0;
		}
		
		Set<Integer> updates = new HashSet<Integer>();
		
		for(int i=0; i<sent.length(); i++) {	// for each token position, update weights if misclassified
			int pred = labels.indexOf(sent.getPredictions().get(i));
			int gold = labels.indexOf(sent.getLabels().get(i));
			if (pred==gold) {
				continue;
			}

			int[] relevantFeatures;
			double[] featureValues;
			boolean hasFirstOrderFeat = false;

			// update gold label feature weights
			
			// - zero-order features
			{
				int[][] relevantFeatureIndices = new int[1][];	// will contain a single array set by the feature extractor
				featureValues = ArabicFeatureExtractor.getInstance().extractZeroOrderFeatureValues(sent, i, featureIndexes, relevantFeatureIndices, false, false);
				relevantFeatures = relevantFeatureIndices[0];
			}
			
			if (relevantFeatures.length==0) throw new RuntimeException("No features found for this token");
			
			for (int h=0; h<relevantFeatures.length; h++){
				int featIndex = getGroundedFeatureIndex(relevantFeatures[h], gold);
				currentWeights[featIndex] += featureValues[h];
				updates.add(featIndex);

			}
			
			// - first-order features
			if (ArabicFeatureExtractor.getInstance().hasFirstOrderFeatures() && i>0) {
				hasFirstOrderFeat = true;
				int[] firstOrderFeats = ArabicFeatureExtractor.getInstance().extractFirstOrderFeatures(sent, i, featureIndexes, false, false);
				int firstOrderFeat = firstOrderFeats[0];
				int featIndex = getGroundedFeatureIndex(firstOrderFeat,gold);
				// this is assumed to be a binary feature
				currentWeights[featIndex] += 1.0;
				updates.add(featIndex);
			}
			
			// update predicted label feature weights
			
			// - zero-order features
			{
				int[][] relevantFeatureIndices = new int[1][];	// will contain a single array set by the feature extractor
				featureValues = ArabicFeatureExtractor.getInstance().extractZeroOrderFeatureValues(sent, i, featureIndexes, relevantFeatureIndices, true, false);
				relevantFeatures = relevantFeatureIndices[0];
			}
			
			for (int h=0; h<relevantFeatures.length; h++){
				int featIndex = getGroundedFeatureIndex(relevantFeatures[h],pred);
				currentWeights[featIndex] -= featureValues[h];
				updates.add(featIndex);
			}
			
			// - first-order features
			if (hasFirstOrderFeat) {
				int[] firstOrderFeats = ArabicFeatureExtractor.getInstance().extractFirstOrderFeatures(sent, i, featureIndexes, true, false);
				int firstOrderFeat = firstOrderFeats[0];
				int featIndex = getGroundedFeatureIndex(firstOrderFeat,pred);
				// this is assumed to be a binary feature
				currentWeights[featIndex] -= 1.0;
				updates.add(featIndex);
			}
			
		}
		
		for (int featIndex : updates)	// update running averages to reflect changed weights
			runningAverageWeights[featIndex] = (timestep*runningAverageWeights[featIndex] + currentWeights[featIndex])/(timestep+1);
		
		return updates.size();
	}



	private void addToMap(Map<String, Double> mapToAddTo, Map<String, Double> map, boolean doSubtraction){
		for(String key: map.keySet()){
			Double val1 = mapToAddTo.get(key);
			Double val2 = map.get(key);
			if(val1 == null){
				val1 = 0.0;
			}

			if(doSubtraction){
				val1 += val2;
			}else{
				val1 -= val2;
			}
			mapToAddTo.put(key, val1);
		}
	}

	private void addToMap(Map<String, Double> mapToAddTo, Map<String, Double> map){
		addToMap(mapToAddTo, map, false);
	}


	private void subtractFromMap(Map<String, Double> mapToAddTo, Map<String, Double> map){
		addToMap(mapToAddTo, map, true);
	}


	/**
	 * 1-best MIRA, using Andre and Kevin's paper
	 * 
	 * @param sent
	 * @param intermediateWeights
	 */
/*	private void MIRAUpdate(LabeledSentence sent, double[] intermediateWeights) {
		if(sent.predictionsAreCorrect()){
			return;
		}

		//compute x L2 norm for denominator in MIRA update
		//compute number of incorrect labels (hamming loss)
		double C = 1.0;
		double x2 = 0.0;
		Map<String, Double> featureValuesPredicted;
		Map<String, Double> featureValuesGold;
		Map<String, Double> featureValuesDifferences = new HashMap<String,Double>();
		Map<String,Double> tmpFeatureValues;

		//compute the step size from looking at the whole sentence
		double numWrong=0;
		double scoreGold = 0.0;
		double scorePredicted = 0.0;
		int predictedLabelIndex;
		int correctLabelIndex;
		for(int j=0; j<sent.length(); j++){
			String predLabel = sent.getPredictions().get(j);
			predictedLabelIndex = labels.indexOf(predLabel);
			tmpFeatureValues = ArabicFeatureExtractor.getInstance().extractFeatureValues(sent, j); 
			scorePredicted += computeScore(tmpFeatureValues,intermediateWeights, predictedLabelIndex);
			addToMap(featureValuesDifferences, tmpFeatureValues);

			String corrLabel = sent.getLabels().get(j);
			correctLabelIndex = labels.indexOf(corrLabel);

			tmpFeatureValues = ArabicFeatureExtractor.getInstance().extractFeatureValues(sent, j, false);
			scoreGold += computeScore(tmpFeatureValues,intermediateWeights,  correctLabelIndex);
			subtractFromMap(featureValuesDifferences, tmpFeatureValues);

			if(predictedLabelIndex != correctLabelIndex){
				if (!predLabel.startsWith("O"))
					numWrong += 0.8;
				else
					numWrong+=1.0;
			}
		}

		for(Double val: featureValuesDifferences.values()){
			x2 += val*val;
		}

		double scoreDifference = scorePredicted - scoreGold;
		double update = Math.min(1.0/C, (scoreDifference + numWrong ) /x2); 

		//Now update the features for each word. 
		//It is done this way, rather than a single update for the sentence, 
		//which should be equivalent,
		//due to implementation tricks used for extracting features.
		for(int j=0; j<sent.length(); j++){
			predictedLabelIndex = labels.indexOf(sent.getPredictions().get(j));
			correctLabelIndex = labels.indexOf(sent.getLabels().get(j));
			int indexOffsetForCorrectLabel = correctLabelIndex*featureIndexes.size();
			int indexOffsetForPredictedLabel = predictedLabelIndex*featureIndexes.size();
			int featureIndex;

			featureValuesPredicted = ArabicFeatureExtractor.getInstance().extractFeatureValues(sent, j);
			featureValuesGold = ArabicFeatureExtractor.getInstance().extractFeatureValues(sent, j, false);

			for(String key: featureValuesGold.keySet()){
				featureIndex = featureIndexes.get(key);
				intermediateWeights[featureIndex+indexOffsetForCorrectLabel] += update * featureValuesGold.get(key);
			}
			for(String key: featureValuesPredicted.keySet()){
				featureIndex = featureIndexes.get(key);
				intermediateWeights[featureIndex+indexOffsetForPredictedLabel] -= update * featureValuesPredicted.get(key);
			}

		}


	}

*/

	public void setTestData(List<LabeledSentence> testData) {
		this.testData = testData;	
	}


	public void test(){
		test(finalWeights);
	}

	public void test(double[] weights){

		if(testData == null) return;
		for(LabeledSentence sent: testData){	
			findBestLabelSequenceViterbi(sent, weights);
		}

		evaluatePredictions(testData, labels);
	}

	public void printPredictions(List<LabeledSentence> data, double[] weights){
		if(data == null) return;
		for(LabeledSentence sent: data){	
			findBestLabelSequenceViterbi(sent, weights);
			System.out.println(sent.taggedString());
		}
	}
	
	/** Loads data from the specified file and prints predictions for it on a per-sentence basis. 
	 *  (Combines the behavior of loadData(String,List) and printPredictions(List,double[])
	 *  so as to scale to large test files.)
	 */
	public void printPredictions(String path, List<String> labels, double[] weights) {
		try {
			System.err.print("writing predictions for "+path);
			int nSent = 0;
			for (LabeledSentence sent : new FeatureFileReader(new File(path), labels, binaryFeats, true)) {
				findBestLabelSequenceViterbi(sent, weights);
 				System.out.println(sent.taggedString());
 				if (nSent%1000==0) System.err.print(".");
 				nSent++;
			}
			System.err.println(" done");
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	/**
	 * evaluate predictions using the CoNLL style evaluation.
	 * instances are sequences of words with contiguous labels (e.g., President of the United States)
	 * not just single tokens (e.g., States).
	 *  
	 * @param sentences
	 */
	public static void evaluatePredictions(List<LabeledSentence> sentences, List<String> labels){
		Map<String, Long> numPredicted = new HashMap<String, Long>();
		Map<String, Long> numGold = new HashMap<String, Long>();
		Map<String, Long> numCorrect = new HashMap<String, Long>();

		numPredicted.put("all", new Long(0));
		numGold.put("all", new Long(0));
		numCorrect.put("all", new Long(0));
		for(String label:labels){
			if(label.equals("O")) continue;
			numPredicted.put(label, new Long(0));
			numGold.put(label, new Long(0));
			numCorrect.put(label, new Long(0));
		}

		long tmp;
		Set<String> gold = new HashSet<String>();
		Set<String> pred = new HashSet<String>();
		int start, end;
		String startLabel;
		for(LabeledSentence sent: sentences){
			gold.clear();
			pred.clear();
			for(int i=0;i<sent.length();i++){
				startLabel = sent.getLabels().get(i);
				if(!startLabel.equals("O")){
					start=i;
					end=i;
					while(i+1<sent.length() && sent.getLabels().get(i+1).charAt(0)=='I'){
						end=i+1;
						i++;
					}
					gold.add(startLabel+"\t"+start+"\t"+end);
				}
			}

			for(int i=0;i<sent.length();i++){
				startLabel = sent.getPredictions().get(i);
				if(!startLabel.equals("O")){
					start=i;
					end=i;
					while(i+1<sent.length() && sent.getPredictions().get(i+1).charAt(0)=='I'){
						end=i+1;
						i++;
					}
					pred.add(startLabel+"\t"+start+"\t"+end);
				}
			}

			for(String s: pred){
				String label = s.substring(0, s.indexOf("\t"));
				tmp = numPredicted.get(label);
				numPredicted.put(label, tmp+1);

				tmp = numPredicted.get("all");
				numPredicted.put("all", tmp+1);

				if(gold.contains(s)){
					tmp = numCorrect.get(label);
					numCorrect.put(label, tmp+1);

					tmp = numCorrect.get("all");
					numCorrect.put("all", tmp+1);
					/*}else{
		    //System.err.println("false negative:\t"+s+"\nPRED:\n"+sent.taggedString()+"\nGOLD:\n"+sent.taggedString(false));
		    //System.err.println();
		    String [] parts = s.split("\\t");
		    String phrase = "";
		    start = new Integer(parts[1]);
		    end = new Integer(parts[2]);
		    for(int m=start;m<=end;m++){
		    phrase+=sent.getTokens().get(m)+" ";
		    }
		    System.err.println("false pos:\t"+phrase+"\t"+s);*/
				}
			}

			for(String s: gold){
				String label = s.substring(0, s.indexOf("\t"));
				tmp = numGold.get(label);
				numGold.put(label, tmp+1);

				tmp = numGold.get("all");
				numGold.put("all", tmp+1);

				/*if(!pred.contains(s)){
		//System.err.println("false negative:\t"+s+"\nPRED:\n"+sent.taggedString()+"\nGOLD:\n"+sent.taggedString(false));
		//System.err.println();
		String [] parts = s.split("\\t");
		String phrase = "";
		start = new Integer(parts[1]);
		end = new Integer(parts[2]);
		for(int m=start;m<=end;m++){
		phrase+=sent.getTokens().get(m)+" ";
		}
		System.err.println("false neg:\t"+phrase+"\t"+s);
		}*/
			}
			//System.err.println();


		}

		for(String label:labels){
			if(label.equals("O")) continue;
			if(label.startsWith("I")) continue;

			double p = (double)numCorrect.get(label)/numPredicted.get(label);
			double r = (double)numCorrect.get(label)/numGold.get(label);
			double g = (double)numGold.get(label);
			System.err.println(label+"\tF1:\t"+(2*p*r/(p+r)+"\tP:\t"+p+"\tR:\t"+r+"\tnumGold:\t"+g));
		}
		double p = (double)numCorrect.get("all")/numPredicted.get("all");
		double r = (double)numCorrect.get("all")/numGold.get("all");
		double g = (double)numGold.get("all");
		System.err.println("all\tF1:\t"+(2*p*r/(p+r)+"\tP:\t"+p+"\tR:\t"+r+"\tnumGold:\t"+g));

	}


	/**
	 * initialize dynamic programming tables
	 * used by the Viterbi algorithm
	 * 
	 */
	private void createDPTables() {

		int maxNumTokens = 0;

		/*
	  for(LabeledSentence sent: trainingData){
	  if(sent.length()>maxNumTokens){
	  maxNumTokens = sent.length();
	  }
	  }
		 */

		maxNumTokens = 200;
		dpValues = new double[maxNumTokens][labels.size()];
		dpBackPointers = new int[maxNumTokens][labels.size()];
	}


	/**
	 * compute a dot product of a set of feature values and the corresponding weights.
	 * This involves looking up the appropriate indexes into the weight vector.
	 * 
	 * @param featureValues
	 * @param weights
	 * @param i
	 * @return
	 */
	private double computeScore(int[] relevantFeatureIndices, double[] featureValues, double[] weights, int labelIndex) {
		double dotProduct = 0.0;
		
		if(labelIndex==-1){
			return 0.0;
		}
		
		for(int h=0; h<relevantFeatureIndices.length; h++){
			int index = relevantFeatureIndices[h];
			double val = featureValues[h];
			//if(index != null){ //test set features may not have been instantiated from the training data
				double weight = weights[getGroundedFeatureIndex(index,labelIndex)];
				dotProduct += weight*val;
			//}
		}
		
		return dotProduct;
	}


	/**
	 * before training, loop through the training data 
	 * once to instantiate all the possible features,
	 * so we don't have to worry about null in the HashMaps
	 * 
	 */
	private Iterable<LabeledSentence> createFeatures() throws IOException {
		System.err.print("instantiating features");
		lastFeatureIndex = 0;

		// instantiate first-order features for all possible previous labels
		Set<Integer> firstOrderFeats = (ArabicFeatureExtractor.getInstance().hasFirstOrderFeatures()) ? new HashSet<Integer>() : null;
		
		// create a feature for each label as the previous label, even if not using 
		// first-order features (otherwise it will mess up the cache file format)
		int[] _firstOrderFeats = new int[labels.size()];
		for (int l=0; l<labels.size(); l++) {
			String key = "prevLabel="+labels.get(l);
			if(!featureIndexes.containsKey(key)){
				featureIndexes.put(key, lastFeatureIndex++);
			}
			int featIndex = featureIndexes.get(key);
			if (firstOrderFeats!=null) {
				firstOrderFeats.add(featIndex);
				_firstOrderFeats[l] = featIndex;
			}
		}
		
//		List<LabeledSentence> trainingDataList = null;	// new LinkedList<LabeledSentence>();
		
		// instantiate the rest of the features
		int nSent = 0;
		for(LabeledSentence sent : trainingData){
			for(int i=0; i<sent.length(); i++){
				if(i>0) sent.getPredictions().set(i-1, sent.getLabels().get(i-1));
				final boolean addNewFeatures = true;
				int[][] relevantFeatureIndices = new int[1][];
				double[] featureVals = ArabicFeatureExtractor.getInstance().extractZeroOrderFeatureValues(sent, i, featureIndexes, relevantFeatureIndices, false, addNewFeatures);
				
				// extract first-order features to make sure they're indexed but don't do anything with them
				ArabicFeatureExtractor.getInstance().extractFirstOrderFeatures(sent, i, featureIndexes, false, addNewFeatures);
			}
			
// 			if (trainingDataList!=null) {
// 				trainingDataList.add(new LabeledSentence(relevantFeatures, featureValues, sent.getLabels(), _firstOrderFeats));
// 			}
			
			if (nSent%1000==0) System.err.print(".");
			nSent++;
		}

		//now create the array of feature weights
		int nWeights = labels.size()*featureIndexes.size();
		finalWeights = new double[nWeights];
		System.err.println(" done with "+nSent+" sentences: "+labels.size()+" labels, "+featureIndexes.size()+" lifted features, size "+finalWeights.length+" weight vector");
		
//		return trainingDataList;
		return trainingData;
	}

	
	/** For use in decoding. If useBIO is true, valid bigrams include
	 *     B        I
	 *     B-class1 I-class1
	 *     I-class1 I-class1
	 *     O        B-class1
	 *     I-class1 O
	 *  and invalid bigrams include
	 *     B-class1 I-class2
	 *     O        I-class2
	 *     O        I
	 *     B        I-class2
	 *  where 'class1' and 'class2' are names of chunk classes.
	 *  If useBIO is false, no constraint is applied--all tag bigrams are 
	 *  considered legal.
	 *  For the first token in the sequence, lbl1 should be null.
	 */
	public static boolean legalTagBigram(String lbl1, String lbl2, boolean useBIO) {
		if(useBIO && lbl2.charAt(0) == 'I'){
			if(lbl1==null || lbl1.equals("O"))
				return false;	// disallow O followed by an I tag
			if((lbl1.length()>1)!=(lbl2.length()>1))
				return false;	// only allow "I" without class if previous tag had no class
			if(lbl2.length()>1 && !lbl1.substring(2).equals(lbl2.substring(2)))
				return false;	// disallow an I tag following a tag with a different class
		}
		return true;
	}

	public void findBestLabelSequenceViterbi(LabeledSentence sent, double [] weights){
		findBestLabelSequenceViterbi(sent, weights, false); 
	}


	/**
	 * uses the Viterbi algorithm to find the current best sequence
	 * of labels for a sentence, given the weight vector.
	 * used in both training and testing
	 * 
	 * @param sent
	 * @param weights
	 * @param includeLossTerm whether to perform loss augmented inference (e.g., with MIRA)
	 */
	public void findBestLabelSequenceViterbi(LabeledSentence sent, double [] weights, boolean includeLossTerm){
		boolean useBIO = _opts.getBoolean("useBIO");

		double costAugVal = _opts.getDouble("useCostAug");
		
		//System.out.println("cost aug val: "+ costAugVal);
//		if(useCostAugStr.equals("true"))
//			useCostAug = true;
//		else
//			useCostAug = false;
		

		int numTokens = sent.length();

		if(dpValues.length < numTokens){ //expand the size of the dynamic programming tables if necessary
			dpValues = new double[(int)(numTokens*1.5)][labels.size()];
			dpBackPointers = new int[(int)(numTokens*1.5)][labels.size()];
		}

		String prevLabel;

		for(int i=0;i<numTokens; i++){
			sent.getPredictions().set(i, null);
		}

		//for each token
		for(int i=0; i<numTokens; i++){
			int[][] relevantFeatureIndices = new int[1][];
			double[] featureValues;
			
			featureValues = ArabicFeatureExtractor.getInstance().extractZeroOrderFeatureValues(sent, i, featureIndexes, relevantFeatureIndices, true, false);
								
			//String stem = sent.getStems().get(i);
			//String tok = sent.getTokens().get(i);
			//String pos  = sent.getPOS().get(i);

			//for each current label
			for(int j=0;j<labels.size();j++){
				double maxScore = Double.NEGATIVE_INFINITY;
				int maxIndex = -1;
				
				// score for zero-order features
				double score0 = computeScore(relevantFeatureIndices[0], featureValues, weights, j);
				
				String label = labels.get(j);
				
				// cost-augmented decoding
				if(includeLossTerm && !label.equals(sent.getLabels().get(i))){
					score0 += 1.0;	// base cost of any error
				}
				if(!label.equals(sent.getLabels().get(i)) && label.equals("O")){
						//if(includeLossTerm && label.equals("O")){
					score0 += costAugVal;	// additional cost of erroneously predicting "O"
				}
				
				// consider each possible previous label
				for(int k=0; k<labels.size(); k++){
					prevLabel = labels.get(k);
					if (!legalTagBigram((i==0) ? null : prevLabel, label, useBIO))
						continue;

					// compute current score based on previous scores
					double score = 0.0;
					if(i>0){
						sent.getPredictions().set(i-1, prevLabel);
						score = dpValues[i-1][k];
					}
					
					//the score for the previous label is added on separately here,
					//in order to avoid computing the whole score, which only depends 
					//on the previous label for one feature,
					//a large number of times: O(labels*labels).
					//TODO plus versus times doesn't matter here, right?  Use plus because of numeric overflow

					/**
			   if(!includeLossTerm && (i > 1) &&(!prevLabel.equals(sent.getLabels().get(i-1)) && prevLabel.equals("O"))){
			   //if(includeLossTerm && label.equals("O")){
			   tmpScore += 100.0;

			   }
					 */
					
					// score of moving from label k at the previous position to the current position (i) & label (j)					
					score += score0;
					if (ArabicFeatureExtractor.getInstance().hasFirstOrderFeatures() && i>0) {
						// the relevant first-order feature is assumed to have value 1
						int findex = -1;
						
						int[] findexA = ArabicFeatureExtractor.getInstance().extractFirstOrderFeatures(sent, i, featureIndexes, true, false);
						if (findexA.length>0)
							findex = findexA[0];
							
						if (findex>=0)
							score += weights[getGroundedFeatureIndex(findex, j)];
					}
					
// 					double pLFS = previousLabelFeatureScore(labels.get(k), j, weights);
// 					//System.out.println(sent.getTokens().get(i) + " gold:" + sent.getLabels().get(i)  + " prevLabelScore: " + pLFS + " tmpScore:" + tmpScore + " score:" + score + " label:" + label + " prevLab:" + prevLabel);
// 					if (pLFS > 0){
// 						int rrr = 0;
// 
// 					}
// 					score += tmpScore + previousLabelFeatureScore(labels.get(k), j, weights);
					
				
					// 
// 			   if (prevLabel.equals("O")){
// 			       //score -= 40;
// 			       int eee = 1;
// 			   //System.out.print("  000000  ");
// 			   }
					
					//System.out.println(" PrevLabel:" + prevLabel + " NewScore:" + score + " MaxScore:" + maxScore);

					// find the max of the combined score at the current position
					// and store the backpointer accordingly
					if(score>maxScore){
						//System.out.println("score jumps");
						maxScore = score;
						maxIndex = k;
					}

					//if this is the first token, we don't need to iterate over all possible previous labels, 
					//because there is only one possibility (i.e., null) 
					if(i==0){
						break;
					}
				}
				//System.out.println("max score is" + maxScore);
				dpValues[i][j] = maxScore;
				dpBackPointers[i][j] = maxIndex;
			}
		}

		// decode from the lattice
		//extract predictions from backpointers
		int maxIndex = -1;
		double maxScore = Double.NEGATIVE_INFINITY;
		//first, find the best label for the last token
		for(int j=0; j<labels.size(); j++){
			double score = dpValues[numTokens-1][j];
			if(score > maxScore){
				//diffValue = score - maxScore;
				maxScore = score;
				maxIndex = j;
			}
		}
		//now iterate backwards by following backpointers
		for(int i=numTokens-1;i>=0;i--){
			sent.getPredictions().set(i,labels.get(maxIndex));
			maxIndex = dpBackPointers[i][maxIndex];
		}

//		double max=Double.NEGATIVE_INFINITY; double secondmax=Double.NEGATIVE_INFINITY;
//		double diff = 0;
//		int maxidx=0;
//		for(int i = 0; i<numTokens; i++){
//			for(int j=0; j<labels.size();j++){
//				if(dpValues[i][j]>max){
//					max = dpValues[i][j];
//					maxidx = j;
//				}
//			}
//			for(int j=0; j<labels.size();j++){
//				if(j==maxidx)
//					continue;
//				if(dpValues[i][j]>secondmax){
//					secondmax = dpValues[i][j];
//				}
//			}
//			diff+= max-secondmax;
//		}
//		diff = diff/numTokens;
//		sent.setDiff(diff);
	}


	public double[] getWeights() {
		return finalWeights;
	}

	public String getSavePrefix() {
		return savePrefix;
	}




	public void setSavePrefix(String savePrefix) {
		this.savePrefix = savePrefix;
	}




	public void setMaxIters(int maxIters) {
		this.maxIters = maxIters;
	}



	public int getMaxIters() {
		return maxIters;
	}

	public boolean isPerceptron() {
		return perceptron;
	}

	public void setPerceptron(boolean perceptron) {
		this.perceptron = perceptron;
	}

	private int maxIters = 5;
	private Iterable<LabeledSentence> trainingData;
	private List<LabeledSentence> testData;

	private double [] finalWeights;

	/*
	 * feature weights are stored in an array of size equal to the number
	 * of features times the number of labels
	 * 
	 *  this map goes from feature names (keys) to feature indexes WITHOUT offsets.
	 *  the offsets are equal to the label index times the number of features
	 *  
	 */
	private Map<String,Integer> featureIndexes;  
	private List<String> labels;
	private int lastFeatureIndex = 0;
	private String savePrefix = null;




	private double [][] dpValues;
	private int [][] dpBackPointers;
	private Random rgen;
	private boolean developmentMode;
	private boolean binaryFeats = false;
	private boolean perceptron = false;

	static JSAPResult _opts;

}
