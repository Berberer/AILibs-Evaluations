package subsampling;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.aeonbits.owner.ConfigCache;
import org.apache.commons.lang.time.StopWatch;
import org.apache.commons.math3.ml.distance.ManhattanDistance;

import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.ml.WekaUtil;
import jaicore.ml.core.dataset.IDataset;
import jaicore.ml.core.dataset.sampling.inmemory.ASamplingAlgorithm;
import jaicore.ml.core.dataset.sampling.inmemory.GmeansSampling;
import jaicore.ml.core.dataset.sampling.inmemory.KmeansSampling;
import jaicore.ml.core.dataset.sampling.inmemory.SimpleRandomSampling;
import jaicore.ml.core.dataset.sampling.inmemory.SystematicSampling;
import jaicore.ml.core.dataset.sampling.inmemory.casecontrol.LocalCaseControlSampling;
import jaicore.ml.core.dataset.sampling.inmemory.casecontrol.OSMAC;
import jaicore.ml.core.dataset.sampling.inmemory.stratified.sampling.AttributeBasedStratiAmountSelectorAndAssigner;
import jaicore.ml.core.dataset.sampling.inmemory.stratified.sampling.GMeansStratiAmountSelectorAndAssigner;
import jaicore.ml.core.dataset.sampling.inmemory.stratified.sampling.StratifiedSampling;
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

				// Used dataset
				String datasetName = description.get("dataset");
				Instances data = new Instances(new BufferedReader(
						new FileReader(new File(m.getDatasetFolder() + File.separator + datasetName + ".arff"))));
				data.setClassIndex(data.numAttributes() - 1);
				List<Instances> splits = WekaUtil.getStratifiedSplit(data, seed, 0.8);
				Instances train = splits.get(0);
				Instances test = splits.get(1);
				SimpleDataset datasetTrain = WekaInstancesUtil.wekaInstancesToDataset(train);

				// Size of the sample
				String sampleSizeString = description.get("samplesize");
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
					samplingAlgorithm = new KmeansSampling<SimpleInstance>(seed, new ManhattanDistance());
					break;
				case "AttributeStratified":
					List<Integer> indices = new LinkedList<>();
					while (indices.size() < 2) {
						int index = random.nextInt(datasetTrain.getNumberOfAttributes());
						if (!indices.contains(index)) {
							indices.add(index);
						}
					}
					System.out.println(indices);
					AttributeBasedStratiAmountSelectorAndAssigner<SimpleInstance> aAttribute = new AttributeBasedStratiAmountSelectorAndAssigner<>(indices);
					samplingAlgorithm = new StratifiedSampling<>(aAttribute, aAttribute, random);
					break;
				case "Systematic":
					samplingAlgorithm = new SystematicSampling<>(random);
					break;
				case "LCC":
					samplingAlgorithm = new LocalCaseControlSampling<>(random,
							(int) (0.01d * (double) datasetTrain.size()));
					break;
				case "OSMAC":
					samplingAlgorithm = new OSMAC<>(random, (int) (0.01d * (double) datasetTrain.size()));
					break;
				case "ClassStratified":
					AttributeBasedStratiAmountSelectorAndAssigner<SimpleInstance> aClass = new AttributeBasedStratiAmountSelectorAndAssigner<>();
					samplingAlgorithm = new StratifiedSampling<SimpleInstance>(aClass, aClass, random);
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

				// Save the accuracy into the results hashmap
				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				results.put("samplingTime", samplingTime);
				results.put("trainingTime", trainingTime);
				results.put("achievedSampleSize", subsampledDatasetTrain.size());
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

}
