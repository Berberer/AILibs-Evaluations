package avoidingOversearch.autoML;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.aeonbits.owner.ConfigCache;

import de.upb.crc901.automl.hascowekaml.HASCOClassificationML;
import de.upb.crc901.automl.hascowekaml.WEKAPipelineFactory;
import de.upb.crc901.automl.pipeline.basic.MLPipeline;
import hasco.core.HASCOProblemReduction;
import hasco.core.Util;
import hasco.serialization.ComponentLoader;
import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.ml.WekaUtil;
import jaicore.ml.evaluation.MulticlassEvaluator;
import jaicore.planning.algorithms.forwarddecomposition.ForwardDecompositionHTNPlannerFactory;
import jaicore.planning.graphgenerators.task.tfd.TFDNode;
import jaicore.search.algorithms.interfaces.ISolutionEvaluator;
import jaicore.search.structure.core.GraphGenerator;
import weka.core.Instances;

public class AutoMLExperimenter {

	public static void main(String[] args) {
		IExampleMCCConfig m = ConfigCache.getOrCreate(IExampleMCCConfig.class);
		if (m.getDatasetFolder() == null || !m.getDatasetFolder().exists())
			throw new IllegalArgumentException("config specifies invalid dataset folder " + m.getDatasetFolder());

		ExperimentRunner runner = new ExperimentRunner(new IExperimentSetEvaluator() {

			@Override
			public IExperimentSetConfig getConfig() {
				return m;
			}

			@Override
			public void evaluate(ExperimentDBEntry experimentEntry, SQLAdapter adapter,
					IExperimentIntermediateResultProcessor processor) throws Exception {

				/* get experiment setup */
				Map<String, String> description = experimentEntry.getExperiment().getValuesOfKeyFields();
				String algorithmName = description.get("algorithm");
				String datasetName = description.get("dataset");
				int seed = Integer.valueOf(description.get("seed"));

				// Calculate experiment score
				Instances data = new Instances(new BufferedReader(
						new FileReader(new File(m.getDatasetFolder() + File.separator + datasetName + ".arff"))));
				data.setClassIndex(data.numAttributes() - 1);
				List<Instances> split = WekaUtil.getStratifiedSplit(data, new Random(seed), 0.75f);
				Instances train = split.get(0);
				Instances test = split.get(1);

				File configFile = new File("model/weka/weka-all-autoweka.json");
				HASCOClassificationML hasco = new HASCOClassificationML(configFile);
				ComponentLoader componentLoader = new ComponentLoader();
				componentLoader.loadComponents(configFile);
				HASCOProblemReduction reduction = new HASCOProblemReduction(configFile, "AbstractClassifier", true);
				GraphGenerator<TFDNode, String> graphGenerator = reduction
						.getGraphGeneratorUsedByHASCOForSpecificPlanner(new ForwardDecompositionHTNPlannerFactory<>());
				MulticlassEvaluator mcEaluator = new MulticlassEvaluator(new Random(seed));
				WEKAPipelineFactory pipelineFactory = new WEKAPipelineFactory();
				ISolutionEvaluator<TFDNode, Double> scoreEvaluator = new ISolutionEvaluator<TFDNode, Double>() {
					@Override
					public Double evaluateSolution(List<TFDNode> solutionPath) throws Exception {
						MLPipeline pipeline = pipelineFactory.getComponentInstantiation(
								Util.getSolutionCompositionFromState(componentLoader.getComponents(),
										solutionPath.get(solutionPath.size() - 1).getState()));
						pipeline.buildClassifier(train);
						double[] prediction = pipeline.classifyInstances(test);
						double errorCounter = 0d;
						for (int i = 0; i < test.size(); i++) {
							if (prediction[i] != test.get(i).classValue()) {
								errorCounter++;
							}
						}
						return errorCounter / test.size();
					}

					@Override
					public boolean doesLastActionAffectScoreOfAnySubsequentSolution(List<TFDNode> partialSolutionPath) {
						return false;
					}
				};

				Double score = null;
				switch (algorithmName) {
				case "ml_plan":
					break;
				case "two_phase":
					break;
				case "pareto":
					break;
				case "awa_star":
					break;
				case "r_star":
					break;
				case "mcts":
					break;
				}

				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

}
