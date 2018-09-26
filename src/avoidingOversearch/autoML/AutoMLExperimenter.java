package avoidingOversearch.autoML;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.aeonbits.owner.ConfigCache;

import de.upb.crc901.mlplan.multiclass.wekamlplan.MLPlanWekaClassifier;
import de.upb.crc901.mlplan.multiclass.wekamlplan.weka.WEKAPipelineFactory;
import de.upb.crc901.mlplan.multiclass.wekamlplan.weka.WekaMLPlanWekaClassifier;
import de.upb.crc901.mlplan.multiclass.wekamlplan.weka.model.MLPipeline;
import hasco.core.Util;
import hasco.model.Component;
import hasco.model.ComponentInstance;
import hasco.serialization.ComponentLoader;
import jaicore.basic.SQLAdapter;
import jaicore.experiments.ExperimentDBEntry;
import jaicore.experiments.ExperimentRunner;
import jaicore.experiments.IExperimentIntermediateResultProcessor;
import jaicore.experiments.IExperimentSetConfig;
import jaicore.experiments.IExperimentSetEvaluator;
import jaicore.ml.WekaUtil;
import jaicore.planning.graphgenerators.task.tfd.TFDNode;
import jaicore.search.algorithms.standard.awastar.AWAStarFactory;
import jaicore.search.algorithms.standard.bestfirst.BestFirstFactory;
import jaicore.search.algorithms.standard.bestfirst.nodeevaluation.RandomCompletionBasedNodeEvaluator;
import jaicore.search.algorithms.standard.mcts.UCBPolicy;
import jaicore.search.algorithms.standard.mcts.UCTFactory;
import jaicore.search.algorithms.standard.mcts.UniformRandomPolicy;
import jaicore.search.algorithms.standard.uncertainty.BasicUncertaintySource;
import jaicore.search.algorithms.standard.uncertainty.OversearchAvoidanceConfig;
import jaicore.search.algorithms.standard.uncertainty.UncertaintyORGraphSearchFactory;
import jaicore.search.algorithms.standard.uncertainty.OversearchAvoidanceConfig.OversearchAvoidanceMode;
import jaicore.search.algorithms.standard.uncertainty.paretosearch.CosinusDistanceComparator;
import jaicore.search.core.interfaces.GraphGenerator;
import jaicore.search.core.interfaces.IGraphSearch;
import jaicore.search.core.interfaces.ISolutionEvaluator;
import jaicore.search.model.probleminputs.GeneralEvaluatedTraversalTree;
import jaicore.search.model.probleminputs.GraphSearchProblemInput;
import jaicore.search.model.probleminputs.UncertainlyEvaluatedTraversalTree;
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
				int timeout = Integer.valueOf(description.get("timeout"));
				IGraphSearch<?, ?, TFDNode, String, Double, ?, ?> algorithm = null;
				Double score = null;

				// Prepare dataset
				Instances data = new Instances(new BufferedReader(
						new FileReader(new File(m.getDatasetFolder() + File.separator + datasetName + ".arff"))));
				data.setClassIndex(data.numAttributes() - 1);
				List<Instances> split = WekaUtil.getStratifiedSplit(data, new Random(seed), 0.6f, 0.2f);
				Instances train = split.get(0);
				Instances validate = split.get(1);
				Instances test = split.get(2);

				// Load Haso Components for Weka
				File configFile = new File("model/weka/weka-all-autoweka.json");
				ComponentLoader componentLoader = new ComponentLoader();
				componentLoader.loadComponents(configFile);
				Collection<Component> components = componentLoader.getComponents();
				WEKAPipelineFactory pipelineFactory = new WEKAPipelineFactory();

				// Get Graph generator
				MLPlanWekaClassifier mlplan = new WekaMLPlanWekaClassifier();
				mlplan.setData(data);
				GraphGenerator<TFDNode, String> graphGenerator = mlplan.getGraphGenerator();

				// Create solution evaluator for the search
				ISolutionEvaluator<TFDNode, Double> searchEvaluator = new ISolutionEvaluator<TFDNode, Double>() {
					@Override
					public Double evaluateSolution(List<TFDNode> solutionPath) throws Exception {
						if (solutionPath != null && !solutionPath.isEmpty()) {
							ComponentInstance instance = Util.getSolutionCompositionFromState(
									componentLoader.getComponents(),
									solutionPath.get(solutionPath.size() - 1).getState(), true);
							if (instance != null) {
								MLPipeline pipeline = pipelineFactory
										.getComponentInstantiation(Util.getSolutionCompositionFromState(components,
												solutionPath.get(solutionPath.size() - 1).getState(), true));
								pipeline.buildClassifier(train);
								double[] prediction = pipeline.classifyInstances(validate);
								double errorCounter = 0d;
								for (int i = 0; i < validate.size(); i++) {
									if (prediction[i] != validate.get(i).classValue()) {
										errorCounter++;
									}
								}
								return errorCounter / validate.size();
							} else {
								return Double.MAX_VALUE;
							}
						} else {
							return Double.MAX_VALUE;
						}
					}

					@Override
					public boolean doesLastActionAffectScoreOfAnySubsequentSolution(List<TFDNode> partialSolutionPath) {
						return false;
					}

					@Override
					public void cancel() {
					}
				};

				// Create solution evluator for the final score after the search
				ISolutionEvaluator<TFDNode, Double> scoreEvaluator = new ISolutionEvaluator<TFDNode, Double>() {
					@Override
					public Double evaluateSolution(List<TFDNode> solutionPath) throws Exception {

						if (solutionPath != null && !solutionPath.isEmpty()) {
							ComponentInstance instance = Util.getSolutionCompositionFromState(
									componentLoader.getComponents(),
									solutionPath.get(solutionPath.size() - 1).getState(), true);
							if (instance != null) {

								MLPipeline pipeline = pipelineFactory
										.getComponentInstantiation(Util.getSolutionCompositionFromState(components,
												solutionPath.get(solutionPath.size() - 1).getState(), true));
								pipeline.buildClassifier(train);
								double[] prediction = pipeline.classifyInstances(test);
								double errorCounter = 0d;
								for (int i = 0; i < test.size(); i++) {
									if (prediction[i] != test.get(i).classValue()) {
										errorCounter++;
									}
								}
								return errorCounter / test.size();
							} else {
								return Double.MAX_VALUE;
							}
						} else {
							return Double.MAX_VALUE;
						}
					}

					@Override
					public boolean doesLastActionAffectScoreOfAnySubsequentSolution(List<TFDNode> partialSolutionPath) {
						return false;
					}

					@Override
					public void cancel() {
					}
				};

				// Configure and create algorithm
				RandomCompletionBasedNodeEvaluator<TFDNode, Double> nodeEvaluator = new RandomCompletionBasedNodeEvaluator<>(
						new Random(seed), 3, searchEvaluator);
				nodeEvaluator.setGenerator(graphGenerator);
				nodeEvaluator.setUncertaintySource(new BasicUncertaintySource<>());
				switch (algorithmName) {
				case "best_first":
					BestFirstFactory<GeneralEvaluatedTraversalTree<TFDNode, String, Double>, TFDNode, String, Double> bestFirstFactory = new BestFirstFactory<>();
					bestFirstFactory
							.setProblemInput(new GeneralEvaluatedTraversalTree<>(graphGenerator, nodeEvaluator));
					bestFirstFactory.setTimeoutForFComputation(10000, n -> Double.MAX_VALUE);
					algorithm = bestFirstFactory.getAlgorithm();
					break;
				case "two_phase":
					OversearchAvoidanceConfig<TFDNode, Double> switchConfig = new OversearchAvoidanceConfig<>(
							OversearchAvoidanceMode.TWO_PHASE_SELECTION, seed);
					switchConfig.setExploitationScoreThreshold(0.1);
					switchConfig.setExplorationUncertaintyThreshold(0.1);
					switchConfig.setInterval(50);
					switchConfig.setMinimumSolutionDistanceForExploration(0.75d);
					switchConfig.setSolutionDistanceMetric(new PipelineMetric(components));
					switchConfig.activateDynamicPhaseLengthsAdjustment(timeout);
					UncertaintyORGraphSearchFactory<TFDNode, String, Double> switchFactory = new UncertaintyORGraphSearchFactory<>();
					switchFactory.setConfig(switchConfig);
					switchFactory
							.setProblemInput(new UncertainlyEvaluatedTraversalTree<>(graphGenerator, nodeEvaluator));
					switchFactory.setTimeoutForFComputation(10000, n -> Double.MAX_VALUE);
					break;
				case "pareto":
					OversearchAvoidanceConfig<TFDNode, Double> paretoConfig = new OversearchAvoidanceConfig<>(
							OversearchAvoidanceMode.PARETO_FRONT_SELECTION, seed);
					paretoConfig.setParetoComparator(new CosinusDistanceComparator<>(-1.0d, 1.0d));
					UncertaintyORGraphSearchFactory<TFDNode, String, Double> paretoFactory = new UncertaintyORGraphSearchFactory<>();
					paretoFactory.setConfig(paretoConfig);
					paretoFactory
							.setProblemInput(new UncertainlyEvaluatedTraversalTree<>(graphGenerator, nodeEvaluator));
					paretoFactory.setTimeoutForFComputation(10000, n -> Double.MAX_VALUE);
					algorithm = paretoFactory.getAlgorithm();
					break;
				case "awa_star":
					AWAStarFactory<GeneralEvaluatedTraversalTree<TFDNode, String, Double>, TFDNode, String, Double> awaStarFactory = new AWAStarFactory<>();
					awaStarFactory.setProblemInput(new GeneralEvaluatedTraversalTree<>(graphGenerator, nodeEvaluator));
					algorithm = awaStarFactory.getAlgorithm();
					break;
				case "mcts":
					UCTFactory<TFDNode, String> mctsFactory = new UCTFactory<>();
					mctsFactory.setDefaultPolicy(new UniformRandomPolicy<>(new Random(seed)));
					mctsFactory.setTreePolicy(new UCBPolicy<>(false));
					mctsFactory.setProblemInput(new GraphSearchProblemInput<>(graphGenerator, searchEvaluator));
					mctsFactory.setSeed(seed);
					algorithm = mctsFactory.getAlgorithm();
					break;
				}

				if (algorithm != null) {
					algorithm.setTimeout(timeout * 1000, TimeUnit.MILLISECONDS);
					try {
						algorithm.call();
					} catch (TimeoutException e) {
						System.out.println("algorithm finished with timeout exception, which is ok.");
					}
					score = (algorithm.getBestSeenSolution() != null)
							? scoreEvaluator.evaluateSolution(algorithm.getBestSeenSolution().getNodes())
							: null;
				}

				System.out.println(algorithmName + ": " + score);
				Map<String, Object> results = new HashMap<>();
				results.put("score", score);
				processor.processResults(results);
			}
		});
		runner.randomlyConductExperiments(true);
	}

}
