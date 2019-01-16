package subsampling;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang.time.StopWatch;

import jaicore.ml.WekaUtil;
import jaicore.ml.cache.ReproducibleInstances;
import jaicore.ml.core.dataset.IDataset;
import jaicore.ml.core.dataset.IInstance;
import jaicore.ml.core.dataset.sampling.ASamplingAlgorithm;
import jaicore.ml.core.dataset.sampling.GmeansSampling;
import jaicore.ml.core.dataset.sampling.SimpleRandomSampling;
import jaicore.ml.core.dataset.sampling.SystematicSampling;
import jaicore.ml.core.dataset.sampling.casecontrol.LocalCaseControlSampling;
import jaicore.ml.core.dataset.sampling.casecontrol.OSMAC;
import jaicore.ml.core.dataset.sampling.stratified.sampling.GMeansStratiAmountSelectorAndAssigner;
import jaicore.ml.core.dataset.sampling.stratified.sampling.IStratiAmountSelector;
import jaicore.ml.core.dataset.sampling.stratified.sampling.IStratiAssigner;
import jaicore.ml.core.dataset.sampling.stratified.sampling.StratifiedSampling;
import jaicore.ml.core.dataset.standard.SimpleDataset;
import jaicore.ml.core.dataset.standard.SimpleInstance;
import jaicore.ml.core.dataset.weka.WekaInstancesUtil;
import jaicore.ml.scikitwrapper.ScikitLearnWrapper;
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
		String subsamplingMethod = "Systematic";

		// Used learning model
		String learningModel = "SVM";				

		// Used dataset
		Instances data = ReproducibleInstances.fromOpenML("1525", "4350e421cdc16404033ef1812ea38c01");
		List<Instances> splits = WekaUtil.getStratifiedSplit(data, seed, 0.8);
		Instances train = splits.get(0);
		Instances test = splits.get(1);
		SimpleDataset datasetTrain = WekaInstancesUtil.wekaInstancesToDataset(train);
		System.out.println("TRAIN SIZE: " + datasetTrain.size());
		
		// Size of the sample
		String sampleSizeString = "25p";
		int sampleSize;
		if (sampleSizeString.contains("p")) {
			sampleSizeString = sampleSizeString.replace("p", "");
			double percentage;
			if (sampleSizeString.equals("100")) {
				percentage = 1.0d;
			} else {
				percentage = Double.valueOf("0." + sampleSizeString);
			}
			sampleSize = (int) (datasetTrain.size() * percentage);
		} else {
			sampleSize = Integer.valueOf(sampleSizeString);
		}
		
		// Perform Subsampling
		ASamplingAlgorithm<SimpleInstance> samplingAlgorithm = null;
		GMeansStratiAmountSelectorAndAssigner<SimpleInstance> g = new GMeansStratiAmountSelectorAndAssigner<>(
				seed);
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
		case "ClusterKMeans":
			// TODO: Add creation of K Means clustering sampling
			break;
		case "AttributeStratified":
			// TODO: Add creation of sampling from attribute strati
			break;
		case "Systematic":
			samplingAlgorithm = new SystematicSampling<>(random);
			break;
		case "LLC":
			samplingAlgorithm = new LocalCaseControlSampling<>(random,
					(int) (0.01d * (double) datasetTrain.size()));
			break;
		case "OSMAC":
			samplingAlgorithm = new OSMAC<>(random, (int) (0.01d * (double) datasetTrain.size()));
			break;
		case "ClassStratified":
			samplingAlgorithm = new StratifiedSampling<>(new IStratiAmountSelector<SimpleInstance>() {
				@Override
				public int selectStratiAmount(IDataset<SimpleInstance> ds) {
					Set<Object> classes = new HashSet<>();
					for (SimpleInstance instance : ds) {
						Object y = instance.getTargetValue(new Object().getClass()).getValue();
						if (!classes.contains(y)) {
							classes.add(y);
						}
					}
					return classes.size();
				}

				@Override
				public void setNumCPUs(int numberOfCPUs) {
				}

				@Override
				public int getNumCPUs() {
					return 0;
				}
			}, new IStratiAssigner<SimpleInstance>() {

				private Map<Object, Integer> classIndices = new HashMap<>();
				private int i = 0;

				@Override
				public void init(IDataset<SimpleInstance> dataset, int stratiAmount) {
				}

				@Override
				public int assignToStrati(IInstance datapoint) {
					Object y = datapoint.getTargetValue(new Object().getClass()).getValue();
					if (!classIndices.containsKey(y)) {
						classIndices.put(y, i);
						i++;
					}
					return classIndices.get(y);
				}

				@Override
				public void setNumCPUs(int numberOfCPUs) {
				}

				@Override
				public int getNumCPUs() {
					return 0;
				}
			}, random);
			break;
		}
		samplingAlgorithm.setInput(datasetTrain);
		samplingAlgorithm.setSampleSize(sampleSize);
		

		// Create Stopwatch for time measurements of sampling
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		IDataset<SimpleInstance> subsampledDatasetTrain = samplingAlgorithm.call();
		stopWatch.stop();
		long samplingTime = stopWatch.getTime();
		System.out.println("SAMPLE SIZE: " + subsampledDatasetTrain.size());

		// Create stratified split of the dataset
		Instances sampledInstancesTrain = WekaInstancesUtil.datasetToWekaInstances(subsampledDatasetTrain);

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
			classifier = new ScikitLearnWrapper("MLPClassifier()",
					"from sklearn.neural_network import MLPClassifier");
			break;
		}
		
		// Train classifier and measure time
		stopWatch.reset();
		stopWatch.start();
		classifier.buildClassifier(sampledInstancesTrain);
		stopWatch.stop();
		long trainingTime = stopWatch.getTime();

		// Calculate accuracy on the test split
		double correctCounter = 0d;
		for (Instance instance : test) {
			if (classifier.classifyInstance(instance) == instance.classValue()) {
				correctCounter++;
			}
		}
		double score = correctCounter / (double) test.size();

		System.out.println("FINAL SCORE: " + score);
		System.out.println("SAMPLING TIME: " + samplingTime);
		System.out.println("TRAINING TIME: " + trainingTime);
	}

}
