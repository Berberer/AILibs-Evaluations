package subsampling;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.aeonbits.owner.ConfigCache;

import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.ml.core.dataset.IDataset;
import jaicore.ml.core.dataset.IInstance;
import jaicore.ml.core.dataset.sampling.ASamplingAlgorithm;
import jaicore.ml.core.dataset.sampling.GmeansSampling;
import jaicore.ml.core.dataset.sampling.SimpleRandomSampling;
import jaicore.ml.core.dataset.sampling.stratified.sampling.GMeansStratiAmountSelectorAndAssigner;
import jaicore.ml.core.dataset.sampling.stratified.sampling.StratifiedSampling;
import weka.classifiers.Classifier;
import weka.classifiers.functions.SMO;
import weka.classifiers.trees.J48;
import weka.core.Instances;

public class SubsamplingExperimenter {

	public static void main(String[] args) {
		IExampleMCCConfig m = ConfigCache.getOrCreate(IExampleMCCConfig.class);

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
				double percentage = Double.valueOf("0." + description.get("samplesize"));

				// Used dataset
				String datasetName = description.get("dataset");
				Instances data = new Instances(new BufferedReader(
						new FileReader(new File(m.getDatasetFolder() + File.separator + datasetName + ".arff"))));
				// TODO: Create Dataset for instances.
				IDataset<IInstance> dataset = null;
				// TODO: Create stratisfied split of the dataset
				IDataset<IInstance> train = null;
				IDataset<IInstance> test = null;

				// Perform Subsampling
				ASamplingAlgorithm samplingAlgorithm = null;
				// TODO: Add subsampling with Systematic,LLC,OSMAC,AttributeStratified,AttributeStratifiedSTD,ClassStratified,ClassStratifiedSTD
				switch (subsamplingMethod) {
				case "SimpleRandom":
					samplingAlgorithm = new SimpleRandomSampling(random);
					break;
				case "ClusterGMeans":
					samplingAlgorithm = new GmeansSampling(seed);
					break;
				case "GMeansStratified":
					GMeansStratiAmountSelectorAndAssigner g = new GMeansStratiAmountSelectorAndAssigner(seed);
					samplingAlgorithm = new StratifiedSampling(g, g, random, false);
					break;
				case "GMeansStratifiedSTD":
					GMeansStratiAmountSelectorAndAssigner gSTD = new GMeansStratiAmountSelectorAndAssigner(seed);
					samplingAlgorithm = new StratifiedSampling(gSTD, gSTD, random, true);
					break;
				}
				samplingAlgorithm.setInput(dataset);
				samplingAlgorithm.setSampleSize((int) (dataset.size() * percentage));
				IDataset<IInstance> subsampledDataset = samplingAlgorithm.call();

				Classifier classifier = null;
				switch (learningModel) {
				case "SVM":
					classifier = new SMO();
					break;
				case "DecisionTree":
					classifier = new J48();
					break;
				}
				
				// TODO: Training with train split
				
				// TODO: Calculation of Accuracy.
				double score = 0.0d;

				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

}
