package subsampling;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.aeonbits.owner.ConfigCache;

import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.ml.WekaUtil;
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

public class SubsamplingExperimenter {

	public static void main(String[] args) {
		ISubsamplingConfig m = ConfigCache.getOrCreate(ISubsamplingConfig.class);

		ExperimentRunner runner = new ExperimentRunner(new IExperimentSetEvaluator() {

			@Override
			public IExperimentSetConfig getConfig() {
				return m;
			}

			@Override
			public void evaluate(ExperimentDBEntry experimentEntry, SQLAdapter adapter,
					IExperimentIntermediateResultProcessor processor) throws Exception {

				// Get experiment setup
				Map<String, String> description = experimentEntry.getExperiment().getValuesOfKeyFields();

				// Random Seed
				int seed = Integer.valueOf(description.get("seed"));
				Random random = new Random(seed);

				// Used subsampling method
				String subsamplingMethod = description.get("algorithm");

				// Used learning model
				String learningModel = description.get("model");

				// Size of the sample as an percentage of the dataset
				String sampleSize = description.get("samplesize");
				double percentage;
				if (sampleSize.equals("100")) {
					percentage = 1.0d;
				} else {
					percentage = Double.valueOf("0." + description.get("samplesize"));
				}

				// Used dataset
				String datasetName = description.get("dataset");
				Instances data = new Instances(new BufferedReader(
						new FileReader(new File(m.getDatasetFolder() + File.separator + datasetName + ".arff"))));
				List<Instances> splits = WekaUtil.getStratifiedSplit(data, seed, 0.8);
				Instances train = splits.get(0);
				Instances test = splits.get(1);
				SimpleDataset datasetTrain = WekaInstancesUtil.wekaInstancesToDataset(train);

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
				samplingAlgorithm.setSampleSize((int) (datasetTrain.size() * percentage));
				IDataset<SimpleInstance> subsampledDatasetTrain = samplingAlgorithm.call();

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
				classifier.buildClassifier(sampledInstancesTrain);

				// Calculate accuracy on the test split
				double correctCounter = 0d;
				for (Instance instance : test) {
					if (classifier.classifyInstance(instance) == instance.classValue()) {
						correctCounter++;
					}
				}
				double score = correctCounter / (double) test.size();

				// Save the accuracy into the results hashmap
				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

}