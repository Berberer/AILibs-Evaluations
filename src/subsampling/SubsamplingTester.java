package subsampling;

import java.util.List;
import java.util.Random;

import jaicore.ml.WekaUtil;
import jaicore.ml.cache.ReproducibleInstances;
import jaicore.ml.core.dataset.IDataset;
import jaicore.ml.core.dataset.sampling.ASamplingAlgorithm;
import jaicore.ml.core.dataset.sampling.GmeansSampling;
import jaicore.ml.core.dataset.sampling.SimpleRandomSampling;
import jaicore.ml.core.dataset.sampling.SystematicSampling;
import jaicore.ml.core.dataset.sampling.stratified.sampling.GMeansStratiAmountSelectorAndAssigner;
import jaicore.ml.core.dataset.sampling.stratified.sampling.StratifiedSampling;
import jaicore.ml.core.dataset.standard.SimpleDataset;
import jaicore.ml.core.dataset.standard.SimpleInstance;
import jaicore.ml.skikitwrapper.SkikitLearnWrapper;
import weka.classifiers.Classifier;
import weka.classifiers.functions.SMO;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.J48;
import weka.core.Instance;
import weka.core.Instances;

public class SubsamplingTester {

	public static void main(String[] args) throws Exception {

		// Random Seed
		int seed = 123;
		Random random = new Random(seed);

		// Used subsampling method
		String subsamplingMethod = "GMeansStratified";

		// Used learning model
		String learningModel = "SVM";

		// Size of the sample as an percentage of the dataset
		double percentage = Double.valueOf("0." + "01");

		// Used dataset
		Instances data = ReproducibleInstances.fromOpenML("1525", "4350e421cdc16404033ef1812ea38c01");
		List<Instances> splits = WekaUtil.getStratifiedSplit(data, seed, 0.8);
		Instances train = splits.get(0);
		Instances test = splits.get(1);
		SimpleDataset datasetTrain = WekaInstancesUtil.wekaInstancesToDataset(train);
		System.out.println("Train Size: " + datasetTrain.size());

		// Perform Subsampling
		ASamplingAlgorithm<SimpleInstance> samplingAlgorithm = null;
		GMeansStratiAmountSelectorAndAssigner<SimpleInstance> g = new GMeansStratiAmountSelectorAndAssigner<>(seed);
		// TODO: Add subsampling with
		// LLC,OSMAC,AttributeStratified,ClassStratified
		switch (subsamplingMethod) {
		case "SimpleRandom":
			samplingAlgorithm = new SimpleRandomSampling<>(random);
			break;
		case "ClusterGMeans":
			samplingAlgorithm = new GmeansSampling<>(seed);
			break;
		case "GMeansStratified":
			samplingAlgorithm = new StratifiedSampling<>(g, g, random);
			break;
		case "Systematic":
			samplingAlgorithm = new SystematicSampling<>(random);
			break;
		}
		samplingAlgorithm.setInput(datasetTrain);
		samplingAlgorithm.setSampleSize((int) (datasetTrain.size() * percentage));
		IDataset<SimpleInstance> subsampledDatasetTrain = samplingAlgorithm.call();
		System.out.println("Subsample size: " + subsampledDatasetTrain.size());

		// Create stratified split of the dataset
		Instances sampledInstanesTrain = WekaInstancesUtil.datasetToWekaInstances(subsampledDatasetTrain);


		// Select the classifier and train it on the train split
		Classifier classifier = null;
		switch (learningModel) {
		case "SVM":
			classifier = new SMO();
			break;
		case "DecisionTree":
			classifier = new J48();
			break;
		case "KNN1":
			classifier = new IBk();
			break;
		case "KNN5":
			classifier = new IBk(5);
			break;
		case "MLP":
			classifier = new SkikitLearnWrapper("sklearn/neural_network/MLPClassifier", "", "");
			break;
		}
		classifier.buildClassifier(sampledInstanesTrain);

		// Calculate accuracy on the test split
		double correctCounter = 0d;
		for (Instance instance : test) {
			if (classifier.classifyInstance(instance) == instance.classValue()) {
				correctCounter++;
			}
		}
		double score = correctCounter / (double) test.size();
		
		System.out.println("FINAL SCORE: " + score);
		
	}

}
